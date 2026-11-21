package io.casehub.langchain4j.crag.quarkus;

import dev.langchain4j.rag.content.retriever.ContentRetriever;
import io.casehub.langchain4j.crag.CragContentRetriever;
import io.casehub.langchain4j.crag.CragRetrievalQuality;
import io.casehub.neocortex.rag.RelevanceEvaluator;
import io.quarkus.arc.InjectableBean;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.AmbiguousResolutionException;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.UnsatisfiedResolutionException;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * Makes an unqualified injection of {@link ContentRetriever} resolve to a {@link CragContentRetriever}
 * around the bean the application would otherwise get.
 *
 * <p>The producer is an alternative at priority {@value #PRIORITY}, above tenancy's (100): when the
 * tenancy module is present, the bean it wraps <em>is</em> the tenancy wrapper, giving
 * CRAG(Tenancy(app)) — the tenant filter runs first, so other tenants' content never reaches the
 * evaluator. Without a {@link RelevanceEvaluator} bean the wrapped bean is returned as is.
 */
@ApplicationScoped
public class CragBeans {

    /** This module's alternative priority; above tenancy's so CRAG is the outer decorator. */
    public static final int PRIORITY = 200;

    private static final Logger LOG = Logger.getLogger(CragBeans.class);

    @Inject
    BeanManager beanManager;

    // keepAlive is not read: it is the injection point that keeps the application's retriever beans.
    @Produces
    @Alternative
    @Priority(PRIORITY)
    @ApplicationScoped
    public ContentRetriever cragContentRetriever(@Any Instance<ContentRetriever> keepAlive,
            Instance<RelevanceEvaluator> evaluator, Event<CragRetrievalQuality> quality) {
        ContentRetriever delegate = resolvedBean();
        if (evaluator.isAmbiguous()) {
            throw new AmbiguousResolutionException("casehub-langchain4j-crag found several RelevanceEvaluator beans"
                    + " and cannot tell which one grades retrieved content — keep one, or mark one as the alternative"
                    + " with the highest priority");
        }
        if (!evaluator.isResolvable()) {
            LOG.warn("casehub-langchain4j-crag: no RelevanceEvaluator bean — retrieved content is NOT graded."
                    + " Add neocortex's rag-crossencoder or define a RelevanceEvaluator bean.");
            return delegate;
        }
        return new CragContentRetriever(delegate, evaluator.get(), summary -> quality.fire(new CragRetrievalQuality(summary)));
    }

    /** The {@code ContentRetriever} ArC would inject if this module were absent. */
    private ContentRetriever resolvedBean() {
        List<Bean<?>> candidates = narrow(beanManager.getBeans(ContentRetriever.class, Any.Literal.INSTANCE).stream()
                .filter(b -> b.getBeanClass() != CragBeans.class)
                .toList());
        if (candidates.isEmpty()) {
            throw new UnsatisfiedResolutionException(
                    "casehub-langchain4j-crag found no ContentRetriever bean to grade — define one, or remove the injection point");
        }
        if (candidates.size() > 1) {
            throw new AmbiguousResolutionException("casehub-langchain4j-crag found " + candidates.size()
                    + " ContentRetriever beans and cannot tell which one an unqualified injection means");
        }
        Bean<?> bean = candidates.get(0);
        return (ContentRetriever) beanManager.getReference(bean, ContentRetriever.class,
                beanManager.createCreationalContext(bean));
    }

    /**
     * ArC's resolution rules for an unqualified injection, ignoring alternatives that outrank this
     * module. Copied from {@code TenancyBeans.narrowLikeArc} — keep the two in step.
     */
    static List<Bean<?>> narrow(List<Bean<?>> beans) {
        List<Bean<?>> eligible = beans.stream().filter(b -> !(b.isAlternative() && priority(b) > PRIORITY)).toList();
        if (eligible.size() < beans.size()) {
            beans.stream()
                    .filter(b -> b.isAlternative() && priority(b) > PRIORITY)
                    // This library's own outer decorators (tracking at 300) wrap CRAG by design.
                    .filter(b -> b.getBeanClass() == null || !b.getBeanClass().getName().startsWith("io.casehub.langchain4j."))
                    .filter(b -> b.getQualifiers().stream().anyMatch(Default.class::isInstance))
                    .forEach(b -> LOG.warnf("casehub-langchain4j-crag: the alternative %s (priority %d) outranks the CRAG"
                            + " filter (priority %d) and is injected without it — keep application alternatives below 100",
                            b.getBeanClass() == null ? b : b.getBeanClass().getName(), priority(b), PRIORITY));
        }
        List<Bean<?>> candidates = eligible.stream()
                .filter(b -> b.getQualifiers().stream().anyMatch(Default.class::isInstance))
                .toList();
        if (candidates.size() <= 1) {
            return candidates.isEmpty() ? eligible : candidates;
        }
        List<Bean<?>> alternatives = candidates.stream().filter(Bean::isAlternative).toList();
        if (!alternatives.isEmpty()) {
            int top = alternatives.stream().mapToInt(CragBeans::priority).max().getAsInt();
            return alternatives.stream().filter(b -> priority(b) == top).toList();
        }
        List<Bean<?>> nonDefault = candidates.stream()
                .filter(b -> !(b instanceof InjectableBean<?> injectable && injectable.isDefaultBean()))
                .toList();
        return nonDefault.isEmpty() ? candidates : nonDefault;
    }

    private static int priority(Bean<?> bean) {
        return bean instanceof InjectableBean<?> injectable ? injectable.getPriority() : 0;
    }
}
