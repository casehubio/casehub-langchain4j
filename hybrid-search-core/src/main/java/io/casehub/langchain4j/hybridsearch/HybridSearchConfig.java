package io.casehub.langchain4j.hybridsearch;

import java.util.Arrays;
import java.util.List;

/**
 * Settings for {@link CasehubHybridContentRetriever}.
 *
 * @param corpusNames the neocortex corpora to search, within the current tenant; at least one
 * @param maxResults the most contents one retrieval returns; at least 1
 */
public record HybridSearchConfig(List<String> corpusNames, int maxResults) {

    public static final int DEFAULT_MAX_RESULTS = 10;

    public HybridSearchConfig {
        if (corpusNames == null || corpusNames.isEmpty()) {
            throw new IllegalArgumentException("at least one corpus name is required");
        }
        if (corpusNames.stream().anyMatch(name -> name == null || name.isBlank())) {
            throw new IllegalArgumentException("corpus names must not be blank: " + corpusNames);
        }
        if (maxResults < 1) {
            throw new IllegalArgumentException("maxResults must be at least 1 — got " + maxResults);
        }
        corpusNames = List.copyOf(corpusNames);
    }

    /** Builds a config from a comma-separated corpus list, ignoring blanks around the commas. */
    public static HybridSearchConfig of(String commaSeparatedCorpora, int maxResults) {
        List<String> names = commaSeparatedCorpora == null
                ? List.of()
                : Arrays.stream(commaSeparatedCorpora.split(","))
                        .map(String::trim)
                        .filter(name -> !name.isEmpty())
                        .toList();
        return new HybridSearchConfig(names, maxResults);
    }
}
