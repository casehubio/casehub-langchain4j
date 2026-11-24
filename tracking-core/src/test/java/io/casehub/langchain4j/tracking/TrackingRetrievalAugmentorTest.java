package io.casehub.langchain4j.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.rag.AugmentationRequest;
import dev.langchain4j.rag.AugmentationResult;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.router.DefaultQueryRouter;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import io.casehub.neocortex.rag.RetrievalRecorded;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class TrackingRetrievalAugmentorTest {

    private static final String QUESTION = "how do I reset my password?";
    private static final String KEY = TrackingRetrievalAugmentor.TRACKED_BY_AUGMENTOR_KEY;

    private final RecordingTracker tracker = new RecordingTracker();
    private final List<RetrievalRecorded> events = new CopyOnWriteArrayList<>();

    private static Content doc(String text, String docId) {
        return Content.from(TextSegment.from(text, Metadata.from("source_document_id", docId)));
    }

    private static final QueryTransformer TWO_VARIANTS = q -> List.of(
            Query.from("variant one", q.metadata()), Query.from("variant two", q.metadata()));

    private ContentRetriever tracked(ContentRetriever app) {
        return tracked(app, () -> "a");
    }

    private ContentRetriever tracked(ContentRetriever app, Supplier<String> tenant) {
        return new TrackingContentRetriever(app, tracker, "kb", tenant, events::add);
    }

    private TrackingRetrievalAugmentor tracking(RetrievalAugmentor inner) {
        return tracking(inner, () -> "a");
    }

    private TrackingRetrievalAugmentor tracking(RetrievalAugmentor inner, Supplier<String> tenant) {
        return new TrackingRetrievalAugmentor(inner, tracker, "kb", tenant, events::add);
    }

    private static DefaultRetrievalAugmentor.DefaultRetrievalAugmentorBuilder inner(ContentRetriever retriever) {
        return DefaultRetrievalAugmentor.builder().contentRetriever(retriever).executor(Runnable::run);
    }

    private static AugmentationRequest request(InvocationParameters parameters) {
        UserMessage message = UserMessage.from(QUESTION);
        InvocationContext context = InvocationContext.builder().chatMemoryId("memory-1")
                .invocationParameters(parameters).build();
        return new AugmentationRequest(message, dev.langchain4j.rag.query.Metadata.builder()
                .chatMessage(message).invocationContext(context).build());
    }

    private static AugmentationRequest request() {
        return request(new InvocationParameters());
    }

    private static Object retrievalId(Content content) {
        return content.textSegment().metadata().toMap().get(TrackingContentRetriever.RETRIEVAL_ID_KEY);
    }

    @Test
    void rewritingTransformer_oneRecordUnderUsersText() {
        ContentRetriever app = q -> List.of(doc("passage " + q.text(), "doc-" + q.text()));
        var augmentor = tracking(inner(tracked(app)).queryTransformer(TWO_VARIANTS).build());

        AugmentationResult result = augmentor.augment(request());

        assertThat(tracker.records).singleElement().satisfies(r -> {
            assertThat(r.query().text()).isEqualTo(QUESTION);
            assertThat(r.chunks()).extracting(c -> c.sourceDocumentId())
                    .containsExactlyInAnyOrder("doc-variant one", "doc-variant two");
            assertThat(r.maxResults()).isEqualTo(2);
        });
        assertThat(result.contents()).hasSize(2).allSatisfy(c -> assertThat(retrievalId(c)).isEqualTo("r-1"));
        assertThat(events).singleElement().satisfies(e -> assertThat(e.retrievalId()).isEqualTo("r-1"));
    }

    @Test
    void singleQuery_oneRecord() {
        var augmentor = tracking(inner(tracked(q -> List.of(doc("p", "doc-1")))).build());

        augmentor.augment(request());

        assertThat(tracker.records).singleElement().satisfies(r -> assertThat(r.query().text()).isEqualTo(QUESTION));
    }

    @Test
    void nullInvocationParameters_stillOneRecord() {
        UserMessage message = UserMessage.from(QUESTION);
        var plain = new AugmentationRequest(message, dev.langchain4j.rag.query.Metadata.from(message, "memory-1", List.of()));
        AtomicReference<AugmentationRequest> seen = new AtomicReference<>();
        AtomicReference<Boolean> markedInside = new AtomicReference<>();
        RetrievalAugmentor innerAugmentor = inner(tracked(q -> List.of(doc("p", "doc-1")))).queryTransformer(TWO_VARIANTS).build();
        RetrievalAugmentor spy = r -> {
            seen.set(r);
            markedInside.set(r.metadata().invocationParameters().containsKey(KEY));
            return innerAugmentor.augment(r);
        };

        tracking(spy).augment(plain);

        assertThat(tracker.records).hasSize(1);
        assertThat(markedInside.get()).isTrue();
        assertThat(seen.get().metadata().chatMemoryId()).isEqualTo("memory-1");
    }

    @Test
    void callerInvocationParameters_markerRemovedAfter() {
        InvocationParameters parameters = InvocationParameters.from("tenant-hint", "x");
        AtomicReference<Boolean> markedInside = new AtomicReference<>();
        RetrievalAugmentor spy = r -> {
            markedInside.set(r.metadata().invocationParameters().containsKey(KEY));
            return AugmentationResult.builder().chatMessage(r.chatMessage()).contents(List.of()).build();
        };

        tracking(spy).augment(request(parameters));

        assertThat(markedInside.get()).isTrue();
        assertThat(parameters.containsKey(KEY)).isFalse();
        assertThat((String) parameters.get("tenant-hint")).isEqualTo("x");
    }

    @Test
    void concurrentCallsSharingParameters_eachOneRecord() throws Exception {
        InvocationParameters shared = new InvocationParameters();
        CountDownLatch bInside = new CountDownLatch(1);
        CountDownLatch aDone = new CountDownLatch(1);
        ContentRetriever app = q -> {
            if (Thread.currentThread().getName().equals("call-b")) {
                bInside.countDown();
                try {
                    aDone.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return List.of(doc("p", "doc-1"));
        };
        var augmentor = tracking(inner(tracked(app)).queryTransformer(TWO_VARIANTS).build());
        List<Throwable> failures = new CopyOnWriteArrayList<>();

        Thread b = new Thread(() -> {
            try {
                augmentor.augment(request(shared));
            } catch (Throwable t) {
                failures.add(t);
            }
        }, "call-b");
        b.start();
        assertThat(bInside.await(5, TimeUnit.SECONDS)).isTrue();
        augmentor.augment(request(shared));
        aDone.countDown();
        b.join(5000);

        assertThat(failures).isEmpty();
        assertThat(tracker.records).hasSize(2).allSatisfy(r -> assertThat(r.query().text()).isEqualTo(QUESTION));
        assertThat(shared.containsKey(KEY)).isFalse();
    }

    @Test
    void transformerDroppingMetadata_retrieverRecordsAsToday() {
        QueryTransformer dropping = q -> List.of(Query.from("variant one"), Query.from("variant two"));
        var augmentor = tracking(inner(tracked(q -> List.of(doc("p " + q.text(), "doc-" + q.text()))))
                .queryTransformer(dropping).build());

        augmentor.augment(request());

        assertThat(tracker.records).extracting(r -> r.query().text())
                .containsExactlyInAnyOrder("variant one", "variant two");
    }

    @Test
    void trackerFails_resultUnchanged() {
        List<Content> contents = List.of(doc("p", "doc-1"));
        RetrievalAugmentor stub = r -> AugmentationResult.builder().chatMessage(r.chatMessage()).contents(contents).build();
        tracker.failure = new IllegalStateException("tracker down");

        AugmentationResult result = tracking(stub).augment(request());

        assertThat(result.contents()).isEqualTo(contents);
    }

    @Test
    void delegateFails_markerReleased() {
        InvocationParameters parameters = new InvocationParameters();
        RetrievalAugmentor failing = r -> {
            throw new IllegalArgumentException("boom");
        };

        assertThatThrownBy(() -> tracking(failing).augment(request(parameters)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("boom");
        assertThat(parameters.containsKey(KEY)).isFalse();
    }

    @Test
    void nonUserMessage_passThrough() {
        SystemMessage message = SystemMessage.from("system");
        var req = new AugmentationRequest(message, dev.langchain4j.rag.query.Metadata.builder().chatMessage(message)
                .invocationContext(InvocationContext.builder().invocationParameters(new InvocationParameters()).build())
                .build());
        AugmentationResult expected = AugmentationResult.builder().chatMessage(message).contents(List.of(doc("p", "d"))).build();

        AugmentationResult result = tracking(r -> expected).augment(req);

        assertThat(result).isSameAs(expected);
        assertThat(tracker.records).isEmpty();
    }

    @Test
    void partlyUpstream_recordsUntrackedOnly() {
        Content upstream = Content.from(TextSegment.from("hybrid",
                Metadata.from(Map.of("source_document_id", "doc-1", "_trackingId", "t-9"))));
        RetrievalAugmentor stub = r -> AugmentationResult.builder().chatMessage(r.chatMessage())
                .contents(List.of(upstream, doc("plain", "doc-2"))).build();

        AugmentationResult result = tracking(stub).augment(request());

        assertThat(tracker.records).singleElement().satisfies(r ->
                assertThat(r.chunks()).extracting(c -> c.sourceDocumentId()).containsExactly("doc-2"));
        assertThat(result.contents()).extracting(TrackingRetrievalAugmentorTest::retrievalId).containsExactly("t-9", "r-1");
    }

    @Test
    void allUpstream_noRecordIdsCopied() {
        Content one = Content.from(TextSegment.from("a", Metadata.from(Map.of("source_document_id", "d1", "_trackingId", "t-1"))));
        Content two = Content.from(TextSegment.from("b", Metadata.from(Map.of("source_document_id", "d2", "_trackingId", "t-2"))));
        RetrievalAugmentor stub = r -> AugmentationResult.builder().chatMessage(r.chatMessage()).contents(List.of(one, two)).build();

        AugmentationResult result = tracking(stub).augment(request());

        assertThat(tracker.records).isEmpty();
        assertThat(result.contents()).extracting(TrackingRetrievalAugmentorTest::retrievalId).containsExactly("t-1", "t-2");
    }

    @Test
    void routerToTwoRetrievers_oneRecord() {
        ContentRetriever first = tracked(q -> List.of(doc("one", "doc-1")));
        ContentRetriever second = tracked(q -> List.of(doc("two", "doc-2")));
        var augmentor = tracking(DefaultRetrievalAugmentor.builder().queryRouter(new DefaultQueryRouter(first, second))
                .executor(Runnable::run).build());

        augmentor.augment(request());

        assertThat(tracker.records).singleElement().satisfies(r ->
                assertThat(r.chunks()).extracting(c -> c.sourceDocumentId()).containsExactlyInAnyOrder("doc-1", "doc-2"));
    }

    @Test
    void emptyAggregation_zeroHitRecord() {
        var augmentor = tracking(inner(tracked(q -> List.of(doc("p", "doc-1")))).queryTransformer(TWO_VARIANTS)
                .contentAggregator(m -> List.of()).build());

        augmentor.augment(request());

        assertThat(tracker.records).singleElement().satisfies(r -> assertThat(r.chunks()).isEmpty());
    }

    @Test
    void blankTenant_noRecordAnywhere() {
        var augmentor = tracking(inner(tracked(q -> List.of(doc("p", "doc-1")), () -> " "))
                .queryTransformer(TWO_VARIANTS).build(), () -> " ");

        augmentor.augment(request());

        assertThat(tracker.records).isEmpty();
    }

    @Test
    void resultWithoutContents_noRetrieval_noRecord() {
        // AugmentationResult copies a null contents list to an empty one; no retriever ran
        AugmentationResult expected = AugmentationResult.builder().chatMessage(UserMessage.from(QUESTION)).build();

        AugmentationResult result = tracking(r -> expected).augment(request());

        assertThat(result.contents()).isEmpty();
        assertThat(tracker.records).isEmpty();
    }

    @Test
    void nullDelegate_rejected() {
        assertThatThrownBy(() -> new TrackingRetrievalAugmentor(null, tracker, "kb", () -> "a", e -> {}))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void noRetrieverCalled_noRecord() {
        var augmentor = tracking(DefaultRetrievalAugmentor.builder().queryRouter(q -> List.of())
                .executor(Runnable::run).build());

        augmentor.augment(request());

        assertThat(tracker.records).isEmpty();
    }

    @Test
    void unwrappedRetrieverWithContents_recorded() {
        var augmentor = tracking(inner(q -> List.of(doc("p", "doc-1"))).build());

        augmentor.augment(request());

        assertThat(tracker.records).hasSize(1);
    }

    @Test
    void untrackedRetrievers_emptyResult_zeroHitRecorded() {
        // e.g. EasyRAG: its retriever is not a bean, so nothing counts retrievals
        var augmentor = new TrackingRetrievalAugmentor(inner(q -> List.of()).build(), tracker, "kb", () -> "a",
                events::add, false);

        augmentor.augment(request());

        assertThat(tracker.records).singleElement().satisfies(r -> assertThat(r.chunks()).isEmpty());
    }

    @Test
    void multimodalMessage_retrieversRecordAsBefore() {
        UserMessage multimodal = UserMessage.from(dev.langchain4j.data.message.TextContent.from("what is in"),
                dev.langchain4j.data.message.ImageContent.from("https://example.com/cat.png"));
        var req = new AugmentationRequest(multimodal, dev.langchain4j.rag.query.Metadata.builder().chatMessage(multimodal)
                .invocationContext(InvocationContext.builder().invocationParameters(new InvocationParameters()).build())
                .build());
        ContentRetriever retriever = tracked(q -> List.of(doc("p", "doc-1")));
        // a custom augmentor that accepts multimodal messages and retrieves with its own query
        RetrievalAugmentor custom = r -> AugmentationResult.builder().chatMessage(r.chatMessage())
                .contents(retriever.retrieve(Query.from("cat", r.metadata()))).build();

        tracking(custom).augment(req);

        assertThat(tracker.records).singleElement().satisfies(r -> assertThat(r.query().text()).isEqualTo("cat"));
    }

    @Test
    void blankTenant_upstreamIdsStillCopied() {
        Content upstream = Content.from(TextSegment.from("hybrid",
                Metadata.from(Map.of("source_document_id", "doc-1", "_trackingId", "t-9"))));
        RetrievalAugmentor stub = r -> AugmentationResult.builder().chatMessage(r.chatMessage())
                .contents(List.of(upstream, doc("plain", "doc-2"))).build();

        AugmentationResult result = tracking(stub, () -> " ").augment(request());

        assertThat(tracker.records).isEmpty();
        assertThat(retrievalId(result.contents().get(0))).isEqualTo("t-9");
    }
}
