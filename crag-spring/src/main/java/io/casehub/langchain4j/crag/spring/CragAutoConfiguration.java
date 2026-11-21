package io.casehub.langchain4j.crag.spring;

import dev.langchain4j.rag.content.retriever.ContentRetriever;
import io.casehub.langchain4j.crag.CragContentRetriever;
import io.casehub.langchain4j.crag.CragRetrievalQuality;
import io.casehub.neocortex.rag.RelevanceEvaluator;
import org.jboss.logging.Logger;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * Wraps every {@link ContentRetriever} bean with a {@link CragContentRetriever} when a
 * {@link RelevanceEvaluator} bean exists; without one, retrievers are left as they are and a WARN is
 * logged.
 *
 * <p>The post-processor runs late, after the tenancy module's early one, so with both modules the
 * result is CRAG(Tenancy(app)): the evaluator never sees another tenant's content. Quality summaries
 * are published as {@link CragRetrievalQuality} application events.
 */
@AutoConfiguration
@ConditionalOnClass({ContentRetriever.class, RelevanceEvaluator.class})
public class CragAutoConfiguration {

    /** Runs after tenancy's post-processor so CRAG wraps the tenant-isolated bean. */
    public static final int POST_PROCESSOR_ORDER = Ordered.LOWEST_PRECEDENCE - 100;

    @Bean
    static CragBeanPostProcessor cragBeanPostProcessor(
            ObjectProvider<RelevanceEvaluator> evaluator, ObjectProvider<ApplicationEventPublisher> events) {
        return new CragBeanPostProcessor(evaluator, events);
    }

    static final class CragBeanPostProcessor implements BeanPostProcessor, Ordered {

        private static final Logger LOG = Logger.getLogger(CragBeanPostProcessor.class);

        private final ObjectProvider<RelevanceEvaluator> evaluator;
        private final ObjectProvider<ApplicationEventPublisher> events;

        CragBeanPostProcessor(ObjectProvider<RelevanceEvaluator> evaluator,
                ObjectProvider<ApplicationEventPublisher> events) {
            this.evaluator = evaluator;
            this.events = events;
        }

        @Override
        public int getOrder() {
            return POST_PROCESSOR_ORDER;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (!(bean instanceof ContentRetriever retriever) || bean instanceof CragContentRetriever
                    || bean instanceof RelevanceEvaluator) {
                return bean;
            }
            RelevanceEvaluator current;
            try {
                current = evaluator.getIfAvailable();
            } catch (NoUniqueBeanDefinitionException e) {
                throw new IllegalStateException("casehub-langchain4j-crag found several RelevanceEvaluator beans and"
                        + " cannot tell which one grades retrieved content — keep one, or mark one @Primary", e);
            }
            if (current == null) {
                LOG.warnf("casehub-langchain4j-crag: no RelevanceEvaluator bean — content from ContentRetriever %s"
                        + " is NOT graded", beanName);
                return bean;
            }
            return new CragContentRetriever(retriever, current, summary -> {
                ApplicationEventPublisher publisher = events.getIfAvailable();
                if (publisher != null) {
                    publisher.publishEvent(new CragRetrievalQuality(summary));
                }
            });
        }
    }
}
