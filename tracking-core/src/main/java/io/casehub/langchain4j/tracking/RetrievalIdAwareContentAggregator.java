package io.casehub.langchain4j.tracking;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.query.Query;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Keeps LangChain4j's de-duplication working when contents carry retrieval IDs.
 *
 * <p>LangChain4j compares contents by their text segment, metadata included, so the same passage
 * from two tracked retrievals ({@value TrackingContentRetriever#RETRIEVAL_ID_KEY}, or neocortex's
 * {@code _trackingId}) is not merged by the wrapped aggregator. This decorator removes those keys
 * before delegating and puts them back on each returned content: a passage that appeared in several
 * retrievals is returned once, carrying the IDs of the first retrieval it appeared in (queries,
 * then lists, in the order given). A content the delegate returns that matches no input is returned
 * unchanged; content metadata such as scores is never touched.
 *
 * <pre>{@code
 * DefaultRetrievalAugmentor.builder()
 *     .queryTransformer(expandingTransformer)
 *     .contentRetriever(trackedRetriever)
 *     .contentAggregator(new RetrievalIdAwareContentAggregator(new DefaultContentAggregator()))
 *     .build();
 * }</pre>
 */
public class RetrievalIdAwareContentAggregator implements ContentAggregator {

    private static final List<String> ID_KEYS =
            List.of(TrackingContentRetriever.RETRIEVAL_ID_KEY, TrackingContentRetriever.NEOCORTEX_TRACKING_ID);

    private final ContentAggregator delegate;

    public RetrievalIdAwareContentAggregator(ContentAggregator delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public ContentAggregator delegate() {
        return delegate;
    }

    @Override
    public List<Content> aggregate(Map<Query, Collection<List<Content>>> queryToContents) {
        Map<TextSegment, Map<String, Object>> idsByPassage = new HashMap<>();
        Map<Query, Collection<List<Content>>> stripped = new LinkedHashMap<>();
        queryToContents.forEach((query, lists) -> {
            List<List<Content>> strippedLists = new ArrayList<>(lists.size());
            for (List<Content> contents : lists) {
                strippedLists.add(contents.stream().map(c -> strip(c, idsByPassage)).toList());
            }
            stripped.put(query, strippedLists);
        });
        return delegate.aggregate(stripped).stream().map(c -> restore(c, idsByPassage)).toList();
    }

    private static Content strip(Content content, Map<TextSegment, Map<String, Object>> idsByPassage) {
        TextSegment segment = content.textSegment();
        if (segment == null) {
            return content;
        }
        Map<String, Object> ids = new LinkedHashMap<>();
        Metadata metadata = segment.metadata().copy();
        for (String key : ID_KEYS) {
            Object value = metadata.toMap().get(key);
            if (value != null) {
                ids.put(key, value);
                metadata.remove(key);
            }
        }
        if (ids.isEmpty()) {
            return content;
        }
        TextSegment plain = TextSegment.from(segment.text(), metadata);
        idsByPassage.putIfAbsent(plain, ids);
        return Content.from(plain, contentMetadata(content));
    }

    private static Content restore(Content content, Map<TextSegment, Map<String, Object>> idsByPassage) {
        TextSegment segment = content.textSegment();
        Map<String, Object> ids = segment == null ? null : idsByPassage.get(segment);
        if (ids == null) {
            return content;
        }
        Map<String, Object> metadata = new LinkedHashMap<>(segment.metadata().toMap());
        metadata.putAll(ids);
        return Content.from(TextSegment.from(segment.text(), Metadata.from(metadata)), contentMetadata(content));
    }

    private static Map<ContentMetadata, Object> contentMetadata(Content content) {
        return content.metadata() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(content.metadata());
    }
}
