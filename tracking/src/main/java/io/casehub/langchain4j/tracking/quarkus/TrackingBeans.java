package io.casehub.langchain4j.tracking.quarkus;

import dev.langchain4j.rag.content.retriever.ContentRetriever;
import io.casehub.langchain4j.tracking.TrackingContentRetriever;
import io.casehub.neocortex.rag.RetrievalRecorded;
import io.casehub.neocortex.rag.RetrievalTracker;
import io.casehub.platform.api.identity.CurrentPrincipal;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Makes an unqualified injection of {@link ContentRetriever} resolve to a {@link TrackingContentRetriever}
 * around the bean the application would otherwise get.
 *
 * <p>The producer is an alternative at priority {@value #PRIORITY}, above CRAG's (200) and tenancy's
 * (100): the bean it wraps is the CRAG or tenancy wrapper when those modules are present, giving
 * Tracking(CRAG(Tenancy(app))) — the record holds what the model actually receives. Without a
 * {@link RetrievalTracker} bean the wrapped bean is returned as is.
 */
@ApplicationScoped
public class TrackingBeans {

    /** This module's alternative priority; above CRAG's so tracking is the outermost decorator. */
    public static final int PRIORITY = 300;

    /** Tenant recorded when the application has no {@link CurrentPrincipal} (a single-tenant app). */
    static final String DEFAULT_TENANT = "default";

    private static final Logger LOG = Logger.getLogger(TrackingBeans.class);

    @Inject
    BeanManager beanManager;

    // keepAlive is not read: it is the injection point that keeps the application's retriever beans.
    @Produces
    @Alternative
    @Priority(PRIORITY)
    @ApplicationScoped
    public ContentRetriever trackingContentRetriever(@Any Instance<ContentRetriever> keepAlive,
            Instance<RetrievalTracker> tracker, Instance<CurrentPrincipal> principal, Event<RetrievalRecorded> recorded,
            @ConfigProperty(name = "casehub.langchain4j.tracking.corpus", defaultValue = "default") String corpus) {
        ContentRetriever delegate = resolvedBean();
        if (tracker.isAmbiguous()) {
            throw new AmbiguousResolutionException("casehub-langchain4j-tracking found several RetrievalTracker beans"
                    + " and cannot tell which one records retrievals — keep one, or mark one as the alternative"
                    + " with the highest priority");
        }
        if (!tracker.isResolvable()) {
            LOG.info("casehub-langchain4j-tracking: no RetrievalTracker bean — retrievals are not recorded."
                    + " Add neocortex's casehub-neocortex-rag-tracking (and casehub.rag.tracking.sqlite.path)"
                    + " or define a RetrievalTracker bean.");
            return delegate;
        }
        return new TrackingContentRetriever(delegate, tracker.get(), corpus, tenant(principal), recorded::fire);
    }

    /** Tenant per retrieval: the principal's tenancy ID (blank → {@code null}, not recorded), else {@value #DEFAULT_TENANT}. */
    static Supplier<String> tenant(Instance<CurrentPrincipal> principal) {
        if (principal.isAmbiguous()) {
            // Never fall back to "default" here: that would record every tenant's retrievals together.
            throw new AmbiguousResolutionException("casehub-langchain4j-tracking found several CurrentPrincipal beans"
                    + " and cannot tell which one supplies the tenant — keep one, or mark one as the alternative"
                    + " with the highest priority");
        }
        if (!principal.isResolvable()) {
            LOG.infof("casehub-langchain4j-tracking: no CurrentPrincipal bean — retrievals are recorded under tenant"
                    + " '%s'", DEFAULT_TENANT);
            return () -> DEFAULT_TENANT;
        }
        // Resolved once, as tenancy does: a normal-scoped principal is a client proxy read per call, and a
        // @Dependent one is not re-created (and leaked) on every retrieval.
        CurrentPrincipal current = principal.get();
        AtomicBoolean warned = new AtomicBoolean();
        return () -> {
            String tenant = current.tenancyId();
            if ((tenant == null || tenant.isBlank()) && warned.compareAndSet(false, true)) {
                LOG.warn("casehub-langchain4j-tracking: CurrentPrincipal has no tenancyId — the retrieval is not"
                        + " recorded (never recorded under another tenant)");
            }
            return tenant;
        };
    }

    /** The {@code ContentRetriever} ArC would inject if this module were absent. */
    private ContentRetriever resolvedBean() {
        List<Bean<?>> candidates = narrow(beanManager.getBeans(ContentRetriever.class, Any.Literal.INSTANCE).stream()
                .filter(b -> b.getBeanClass() != TrackingBeans.class)
                .toList());
        if (candidates.isEmpty()) {
            throw new UnsatisfiedResolutionException(
                    "casehub-langchain4j-tracking found no ContentRetriever bean to track — define one, or remove the injection point");
        }
        if (candidates.size() > 1) {
            throw new AmbiguousResolutionException("casehub-langchain4j-tracking found " + candidates.size()
                    + " ContentRetriever beans and cannot tell which one an unqualified injection means");
        }
        Bean<?> bean = candidates.get(0);
        return (ContentRetriever) beanManager.getReference(bean, ContentRetriever.class,
                beanManager.createCreationalContext(bean));
    }

    /**
     * ArC's resolution rules for an unqualified injection, ignoring alternatives that outrank this
     * module. Copied from {@code CragBeans.narrow} and {@code TenancyBeans.narrowLikeArc} — keep the
     * three in step.
     */
    static List<Bean<?>> narrow(List<Bean<?>> beans) {
        List<Bean<?>> eligible = beans.stream().filter(b -> !(b.isAlternative() && priority(b) > PRIORITY)).toList();
        if (eligible.size() < beans.size()) {
            beans.stream()
                    .filter(b -> b.isAlternative() && priority(b) > PRIORITY)
                    .filter(b -> b.getBeanClass() == null || !b.getBeanClass().getName().startsWith("io.casehub.langchain4j."))
                    .filter(b -> b.getQualifiers().stream().anyMatch(Default.class::isInstance))
                    .forEach(b -> LOG.warnf("casehub-langchain4j-tracking: the alternative %s (priority %d) outranks"
                            + " retrieval tracking (priority %d) and is injected without it — keep application"
                            + " alternatives below 100",
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
            int top = alternatives.stream().mapToInt(TrackingBeans::priority).max().getAsInt();
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
