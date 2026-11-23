package io.casehub.langchain4j.governance.gate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.casehub.work.api.WorkItemCreateRequest;
import io.casehub.work.api.view.WorkItemPage;
import io.casehub.work.api.view.WorkItemView;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * casehub-work's generated {@code /api/work/items} REST endpoints (list-all, create, add-label) over a
 * {@link FakeWorkItemApi}. Like the real server, the tenant comes from the bearer token; an unknown token
 * gets 401.
 */
public final class FakeWorkServer implements AutoCloseable {

    private static final String BASE = "/api/work/items/";

    private final FakeWorkItemApi work;
    private final Map<String, String> tenantByToken;
    private final ObjectMapper mapper = HttpWorkItemGateway.mapper();
    private final HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final Deque<int[]> statusOverrides = new ArrayDeque<>();
    private final Deque<String> bodyOverrides = new ArrayDeque<>();
    private volatile Duration delay;

    public FakeWorkServer(FakeWorkItemApi work, Map<String, String> tenantByToken) throws IOException {
        this.work = work;
        this.tenantByToken = Map.copyOf(tenantByToken);
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(BASE, this::handle);
        server.start();
    }

    public URI url() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    /** Every request seen: method, path and decoded query. */
    public List<String> requests() {
        return requests;
    }

    /** The next request is answered with this status and body instead of the store. */
    public synchronized void respondNext(int status, String body) {
        statusOverrides.add(new int[] {status});
        bodyOverrides.add(body == null ? "" : body);
    }

    /** The next request is answered only after this delay. */
    public void delayNext(Duration delay) {
        this.delay = delay;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
            String path = exchange.getRequestURI().getPath().substring(BASE.length());
            requests.add(exchange.getRequestMethod() + " " + path + " " + query);
            Duration wait = delay;
            if (wait != null) {
                delay = null;
                Thread.sleep(wait.toMillis());
            }
            synchronized (this) {
                if (!statusOverrides.isEmpty()) {
                    send(exchange, statusOverrides.poll()[0], bodyOverrides.poll());
                    return;
                }
            }
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            String tenant = auth != null && auth.startsWith("Bearer ") ? tenantByToken.get(auth.substring(7)) : null;
            if (tenant == null) {
                send(exchange, 401, "");
                return;
            }
            if (path.equals("list-all") && exchange.getRequestMethod().equals("GET")) {
                WorkItemPage page = work.api.listAll(null, null, query.get("label"), null, tenant,
                        intOrNull(query.get("offset")), intOrNull(query.get("limit")));
                send(exchange, 200, mapper.writeValueAsString(page));
            } else if (path.equals("create") && exchange.getRequestMethod().equals("POST")) {
                WorkItemCreateRequest request = mapper.readValue(exchange.getRequestBody(), WorkItemCreateRequest.class);
                send(exchange, 201, mapper.writeValueAsString(work.api.create(request, tenant)));
            } else if (path.startsWith("add-label/") && exchange.getRequestMethod().equals("POST")) {
                UUID id = UUID.fromString(path.substring("add-label/".length()));
                WorkItemView updated = work.items.containsKey(id)
                        ? work.api.addLabel(id, query.get("path"), query.get("appliedBy"), tenant)
                        : null;
                send(exchange, updated == null ? 404 : 200, updated == null ? "" : mapper.writeValueAsString(updated));
            } else {
                send(exchange, 404, "");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> values = new HashMap<>();
        if (raw == null || raw.isEmpty()) {
            return values;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            values.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return values;
    }

    private static Integer intOrNull(String value) {
        return value == null ? null : Integer.valueOf(value);
    }
}
