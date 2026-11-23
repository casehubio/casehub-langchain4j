package io.casehub.langchain4j.governance.gate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.casehub.work.api.WorkItemCreateRequest;
import io.casehub.work.api.WorkItemStatus;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link WorkItemGateway} over a remote casehub-work service's REST endpoints
 * ({@code <baseUrl>/api/work/items/list-all}, {@code create}, {@code add-label/{id}}).
 *
 * <p>casehub-work takes the tenant from the caller's token, so every call sends the bearer token
 * {@link WorkTokenProvider} gives for the call's tenant; a tenant without one is refused before any
 * request. Non-2xx responses, I/O failures and timeouts throw {@link WorkItemGatewayException}, which
 * the gate turns into a refusal. A status this client does not know maps to {@code null} — still waiting.
 */
public final class HttpWorkItemGateway implements WorkItemGateway {

    private static final int PAGE = 50;
    private static final ObjectMapper MAPPER = mapper();

    private final URI base;
    private final WorkTokenProvider tokens;
    private final Duration timeout;
    private final HttpClient client;

    public HttpWorkItemGateway(URI baseUrl, WorkTokenProvider tokens, Duration timeout) {
        Objects.requireNonNull(baseUrl, "baseUrl required");
        String url = baseUrl.toString();
        this.base = URI.create((url.endsWith("/") ? url : url + "/") + "api/work/items/");
        this.tokens = Objects.requireNonNull(tokens, "tokens required");
        this.timeout = Objects.requireNonNull(timeout, "timeout required");
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public List<GateItem> findByLabel(String label, String tenancyId) {
        List<GateItem> items = new ArrayList<>();
        int offset = 0;
        while (true) {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("label", label);
            query.put("offset", Integer.toString(offset));
            query.put("limit", Integer.toString(PAGE));
            Page page = read(send(tenancyId, request("list-all", query).GET(), 200), Page.class);
            List<Item> found = page.items() == null ? List.of() : page.items();
            found.forEach(item -> items.add(item.toGateItem()));
            if (!page.hasMore() || found.isEmpty()) {
                return items;
            }
            offset += found.size();
        }
    }

    @Override
    public GateItem create(WorkItemCreateRequest request, String tenancyId) {
        String body;
        try {
            body = MAPPER.writeValueAsString(request);
        } catch (IOException e) {
            throw new IllegalArgumentException("work item request cannot be serialised", e);
        }
        HttpRequest.Builder post = request("create", Map.of())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        return read(send(tenancyId, post, 201), Item.class).toGateItem();
    }

    @Override
    public void addLabel(UUID id, String path, String appliedBy, String tenancyId) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("path", path);
        query.put("appliedBy", appliedBy);
        send(tenancyId, request("add-label/" + id, query).POST(HttpRequest.BodyPublishers.noBody()), 200);
    }

    /** The JSON mapping both ends of the casehub-work REST API use: ISO-8601 instants, unknown fields ignored. */
    static ObjectMapper mapper() {
        SimpleModule instants = new SimpleModule("casehub-lc4j-instants")
                .addSerializer(Instant.class, new JsonSerializer<>() {
                    @Override
                    public void serialize(Instant value, JsonGenerator gen, SerializerProvider serializers)
                            throws IOException {
                        gen.writeString(value.toString());
                    }
                })
                .addDeserializer(Instant.class, new JsonDeserializer<>() {
                    @Override
                    public Instant deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                        return Instant.parse(parser.getValueAsString());
                    }
                });
        return new ObjectMapper().registerModule(instants)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    private HttpRequest.Builder request(String path, Map<String, String> query) {
        String suffix = query.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
        return HttpRequest.newBuilder(base.resolve(suffix.isEmpty() ? path : path + "?" + suffix))
                .timeout(timeout)
                .header("Accept", "application/json");
    }

    private String send(String tenancyId, HttpRequest.Builder builder, int expected) {
        String token = tokens.token(tenancyId).orElseThrow(() -> new IllegalStateException(
                "no casehub-work token for tenant '" + tenancyId + "'"));
        HttpRequest request = builder.header("Authorization", "Bearer " + token).build();
        String target = request.method() + " " + request.uri().getPath();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new WorkItemGatewayException("casehub-work " + target + " failed: " + e.getClass().getSimpleName(),
                    -1, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkItemGatewayException("casehub-work " + target + " interrupted", -1, e);
        }
        if (response.statusCode() != expected) {
            throw new WorkItemGatewayException("casehub-work " + target + " answered " + response.statusCode(),
                    response.statusCode(), null);
        }
        return response.body();
    }

    private static <T> T read(String body, Class<T> type) {
        try {
            return MAPPER.readValue(body, type);
        } catch (IOException e) {
            throw new WorkItemGatewayException("casehub-work response cannot be read as " + type.getSimpleName(), -1, e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Page(List<Item> items, boolean hasMore) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Label(String path) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Item(UUID id, String status, String createdBy, String callerRef, Instant createdAt,
            List<Label> labels, String candidateGroups) {

        GateItem toGateItem() {
            Set<String> paths = labels == null ? Set.of()
                    : labels.stream().map(Label::path).filter(Objects::nonNull).collect(Collectors.toSet());
            return new GateItem(id, status(status), createdBy, callerRef, createdAt, paths, candidateGroups);
        }

        private static WorkItemStatus status(String value) {
            if (value == null) {
                return null;
            }
            try {
                return WorkItemStatus.valueOf(value);
            } catch (IllegalArgumentException unknown) {
                return null;
            }
        }
    }
}
