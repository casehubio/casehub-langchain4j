package io.casehub.langchain4j.tracking.spring;

import dev.langchain4j.rag.content.retriever.ContentRetriever;
import io.casehub.langchain4j.tracking.TrackingContentRetriever;
import io.casehub.neocortex.rag.RetrievalTracker;
import io.casehub.platform.api.identity.CurrentPrincipal;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.jboss.logging.Logger;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/**
 * Wraps every {@link ContentRetriever} bean with a {@link TrackingContentRetriever} when a
 * {@link RetrievalTracker} bean exists; without one, retrievers are left as they are and an INFO is
 * logged. neocortex's {@code casehub-neocortex-rag-tracking-spring} supplies a SQLite tracker
 * ({@code casehub.rag.tracking.sqlite.path}); an application may declare its own instead.
 *
 * <p>The post-processor runs after CRAG's and tenancy's, so with all three modules the result is
 * Tracking(CRAG(Tenancy(app))). The hybrid-search bean ({@value #HYBRID_BEAN_NAME}) is not wrapped: it
 * searches its own corpora, so recording it under this module's corpus name would split those
 * documents across two names; neocortex's {@code rag-tracking-spring} tracks it instead, under its
 * own corpora ({@code casehub.rag.tracking.enabled=true}). Recorded retrievals are
 * published as neocortex {@code RetrievalRecorded} application events.
 */
@AutoConfiguration
@ConditionalOnClass({ContentRetriever.class, RetrievalTracker.class})
public class TrackingAutoConfiguration {

    /** Runs after CRAG's ({@code LOWEST_PRECEDENCE - 100}) so tracking is the outermost decorator. */
    public static final int POST_PROCESSOR_ORDER = Ordered.LOWEST_PRECEDENCE - 50;

    /** The corpus name recorded for every wrapped retriever. */
    public static final String CORPUS_PROPERTY = "casehub.langchain4j.tracking.corpus";

    /** hybrid-search-spring's bean name; a string so hybrid-search is not a dependency. */
    static final String HYBRID_BEAN_NAME = "casehubHybridRetriever";

    static final String DEFAULT_TENANT = "default";

    @Bean
    static TrackingBeanPostProcessor trackingBeanPostProcessor(ObjectProvider<RetrievalTracker> tracker,
            ObjectProvider<CurrentPrincipal> principal, ObjectProvider<ApplicationEventPublisher> events,
            Environment environment) {
        return new TrackingBeanPostProcessor(tracker, principal, events, environment);
    }

    static final class TrackingBeanPostProcessor implements BeanPostProcessor, Ordered {

        private static final Logger LOG = Logger.getLogger(TrackingBeanPostProcessor.class);

        private final ObjectProvider<RetrievalTracker> tracker;
        private final ObjectProvider<CurrentPrincipal> principal;
        private final ObjectProvider<ApplicationEventPublisher> events;
        private final Environment environment;
        private final AtomicBoolean reportedDefaultTenant = new AtomicBoolean();
        private final AtomicBoolean warnedBlankTenant = new AtomicBoolean();

        TrackingBeanPostProcessor(ObjectProvider<RetrievalTracker> tracker, ObjectProvider<CurrentPrincipal> principal,
                ObjectProvider<ApplicationEventPublisher> events, Environment environment) {
            this.tracker = tracker;
            this.principal = principal;
            this.events = events;
            this.environment = environment;
        }

        @Override
        public int getOrder() {
            return POST_PROCESSOR_ORDER;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (!(bean instanceof ContentRetriever retriever) || bean instanceof TrackingContentRetriever
                    || bean instanceof RetrievalTracker || HYBRID_BEAN_NAME.equals(beanName)) {
                return bean;
            }
            RetrievalTracker current;
            try {
                current = tracker.getIfAvailable();
            } catch (NoUniqueBeanDefinitionException e) {
                throw new IllegalStateException("casehub-langchain4j-tracking found several RetrievalTracker beans and"
                        + " cannot tell which one records retrievals — keep one, or mark one @Primary", e);
            }
            if (current == null) {
                LOG.infof("casehub-langchain4j-tracking: no RetrievalTracker bean — retrievals of ContentRetriever %s"
                        + " are not recorded", beanName);
                return bean;
            }
            String corpus = environment.getProperty(CORPUS_PROPERTY, "default");
            return new TrackingContentRetriever(retriever, current, corpus, tenant(), recorded -> {
                ApplicationEventPublisher publisher = events.getIfAvailable();
                if (publisher != null) {
                    publisher.publishEvent(recorded);
                }
            });
        }

        /** The principal's tenancy ID per retrieval (blank → not recorded), else {@value #DEFAULT_TENANT}. */
        private Supplier<String> tenant() {
            return () -> {
                CurrentPrincipal current = principal.getIfAvailable();
                if (current == null) {
                    if (reportedDefaultTenant.compareAndSet(false, true)) {
                        LOG.infof("casehub-langchain4j-tracking: no CurrentPrincipal bean — retrievals are recorded"
                                + " under tenant '%s'", DEFAULT_TENANT);
                    }
                    return DEFAULT_TENANT;
                }
                String tenant = current.tenancyId();
                if ((tenant == null || tenant.isBlank()) && warnedBlankTenant.compareAndSet(false, true)) {
                    LOG.warn("casehub-langchain4j-tracking: CurrentPrincipal has no tenancyId — the retrieval is not"
                            + " recorded (never recorded under another tenant)");
                }
                return tenant;
            };
        }
    }
}
