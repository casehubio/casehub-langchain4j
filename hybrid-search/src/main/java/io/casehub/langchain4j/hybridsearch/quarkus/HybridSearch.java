package io.casehub.langchain4j.hybridsearch.quarkus;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.inject.Qualifier;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Selects the neocortex-backed hybrid {@code ContentRetriever}.
 *
 * <p>The retriever carries this qualifier so that it is <em>not</em> a {@code @Default} bean: an
 * unqualified {@code @Inject ContentRetriever} keeps resolving to the application's own retriever.
 */
@Qualifier
@Retention(RUNTIME)
@Target({TYPE, METHOD, FIELD, PARAMETER})
public @interface HybridSearch {}
