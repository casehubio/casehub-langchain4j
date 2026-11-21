package io.casehub.langchain4j.crag;

import io.casehub.neocortex.rag.RetrievalQuality;
import java.util.Objects;

/**
 * Event published after each graded retrieval of a {@link CragContentRetriever}.
 *
 * <p>Wraps neocortex's {@link RetrievalQuality} so observers can tell this module's summaries from
 * the ones neocortex's own CRAG pipeline publishes as a bare {@code RetrievalQuality}.
 */
public record CragRetrievalQuality(RetrievalQuality quality) {

    public CragRetrievalQuality {
        Objects.requireNonNull(quality, "quality required");
    }
}
