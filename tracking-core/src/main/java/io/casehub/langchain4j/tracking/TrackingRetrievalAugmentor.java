package io.casehub.langchain4j.tracking;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.rag.AugmentationRequest;
import dev.langchain4j.rag.AugmentationResult;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Metadata;
import io.casehub.neocortex.rag.RetrievalRecorded;
import io.casehub.neocortex.rag.RetrievalTracker;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * Records each question an application's {@link RetrievalAugmentor} answers as <b>one</b> retrieval in
 * neocortex's {@link RetrievalTracker}: the user's message text and the contents after aggregation —
 * also when the augmentor rewrites the question into several queries (expansion, compression, HyDE).
 *
 * <p>While it runs, the request's {@code InvocationParameters} carry {@value #TRACKED_BY_AUGMENTOR_KEY},
 * so {@link TrackingContentRetriever}s inside the augmentor record nothing. Returned contents carry
 * the retrieval ID under {@value TrackingContentRetriever#RETRIEVAL_ID_KEY}; contents already recorded
 * upstream (neocortex's {@code _trackingId}) keep their own ID and are not recorded again. Tracking
 * never changes or fails the augmentation.
 */
public class TrackingRetrievalAugmentor implements RetrievalAugmentor {

    /** {@code InvocationParameters} key marking a question this decorator records. */
    public static final String TRACKED_BY_AUGMENTOR_KEY = "casehub.langchain4j.tracking.augmentor";

    private static final Logger LOG = Logger.getLogger(TrackingRetrievalAugmentor.class);

    private final RetrievalAugmentor delegate;
    private final RetrievalRecorder recorder;

    /**
     * @param tenantId the tenant to record under, read per question; {@code null} or blank means the
     *        question is not recorded
     */
    public TrackingRetrievalAugmentor(RetrievalAugmentor delegate, RetrievalTracker tracker, String corpusName,
            Supplier<String> tenantId, Consumer<RetrievalRecorded> recorded) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.recorder = new RetrievalRecorder(tracker, corpusName, tenantId, recorded, LOG);
    }

    /** The augmentor this one records for. */
    public RetrievalAugmentor delegate() {
        return delegate;
    }

    @Override
    public AugmentationResult augment(AugmentationRequest request) {
        if (!(request.chatMessage() instanceof UserMessage user)) {
            return delegate.augment(request);
        }
        InvocationParameters parameters = request.metadata().invocationParameters();
        AugmentationRequest marked = parameters == null ? withFreshParameters(request) : request;
        InvocationParameters marker = parameters == null ? marked.metadata().invocationParameters() : parameters;
        int before = acquire(marker);
        AugmentationResult result;
        int retrievals;
        try {
            result = delegate.augment(marked);
        } finally {
            retrievals = release(marker) - before;
        }
        if (result == null || result.contents() == null) {
            return result;
        }
        if (retrievals <= 0 && result.contents().isEmpty()) {
            return result;  // nothing was retrieved: not a zero-hit question
        }
        try {
            List<Content> stamped = recorder.record(user.singleText(), result.contents(), true);
            return AugmentationResult.builder().chatMessage(result.chatMessage()).contents(stamped).build();
        } catch (RuntimeException e) {
            LOG.warnf(e, "Retrieval tracking failed — returning the augmentation unchanged");
            return result;
        }
    }

    /**
     * Counts nested or concurrent calls sharing one {@code InvocationParameters}; returns the number of
     * stood-down retrievals so far (calls sharing the object share that count — an approximation that
     * only matters for telling "nothing retrieved" from "nothing found").
     */
    private static int acquire(InvocationParameters parameters) {
        synchronized (parameters) {
            AtomicInteger calls = parameters.get(TRACKED_BY_AUGMENTOR_KEY);
            if (calls == null) {
                calls = new AtomicInteger();
                parameters.put(TRACKED_BY_AUGMENTOR_KEY, calls);
                parameters.put(RetrievalRecorder.RETRIEVALS_KEY, new AtomicInteger());
            }
            calls.incrementAndGet();
            return parameters.<AtomicInteger>get(RetrievalRecorder.RETRIEVALS_KEY).get();
        }
    }

    /** Releases one call; returns the stood-down retrievals counted up to now. */
    private static int release(InvocationParameters parameters) {
        synchronized (parameters) {
            AtomicInteger retrievals = parameters.get(RetrievalRecorder.RETRIEVALS_KEY);
            int count = retrievals == null ? 0 : retrievals.get();
            AtomicInteger calls = parameters.get(TRACKED_BY_AUGMENTOR_KEY);
            if (calls == null || calls.decrementAndGet() <= 0) {
                parameters.asMap().remove(TRACKED_BY_AUGMENTOR_KEY);
                parameters.asMap().remove(RetrievalRecorder.RETRIEVALS_KEY);
            }
            return count;
        }
    }

    /** A copy of the request whose invocation context has its own {@code InvocationParameters}. */
    private static AugmentationRequest withFreshParameters(AugmentationRequest request) {
        Metadata metadata = request.metadata();
        InvocationContext context = metadata.invocationContext();
        InvocationContext copy = InvocationContext.builder()
                .invocationId(context.invocationId())
                .interfaceName(context.interfaceName())
                .methodName(context.methodName())
                .methodArguments(context.methodArguments())
                .userMessage(context.userMessage())
                .chatMemoryId(context.chatMemoryId())
                .defaultRequestParameters(context.defaultRequestParameters())
                .modelProvider(context.modelProvider())
                .managedParameters(context.managedParameters())
                .timestamp(context.timestamp())
                .invocationParameters(new InvocationParameters())
                .build();
        Metadata copied = Metadata.builder()
                .chatMessage(metadata.chatMessage())
                .systemMessage(metadata.systemMessage())
                .chatMemory(metadata.chatMemory())
                .invocationContext(copy)
                .build();
        return new AugmentationRequest(request.chatMessage(), copied);
    }
}
