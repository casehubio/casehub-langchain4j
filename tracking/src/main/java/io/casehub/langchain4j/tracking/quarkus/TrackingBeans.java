package io.casehub.langchain4j.tracking.quarkus;

import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import io.casehub.langchain4j.tracking.TrackingContentRetriever;
import io.casehub.langchain4j.tracking.TrackingRetrievalAugmentor;
import io.casehub.neocortex.rag.RetrievalRecorded;
import io.casehub.neocortex.rag.RetrievalTracker;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.quarkus.arc.InjectableBean;
import io.quarkus.arc.impl.CreationalContextImpl;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.spi.CreationalContext;
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

    /** Name of the synthetic tracking augmentor the deployment module registers ({@code @Named} keeps {@code @Default}). */
    public static final String AUGMENTOR_BEAN_NAME = "casehubLangchain4jTrackingAugmentor";

    /** Tenant recorded when the application has no {@link CurrentPrincipal} (a single-tenant app). */
    static final String DEFAULT_TENANT = "default";

    private static final Logger LOG = Logger.getLogger(TrackingBeans.class);

    @Inject
    BeanManager beanManager;

    @Inject
    Instance<RetrievalTracker> trackers;

    private final AtomicBoolean reportedNoTracker = new AtomicBoolean();

    @Inject
    Instance<CurrentPrincipal> principals;

    @Inject
    Event<RetrievalRecorded> recordedEvents;

    @ConfigProperty(name = "casehub.langchain4j.tracking.corpus", defaultValue = "default")
    String corpusName;

    // keepAlive is not read: it is the injection point that keeps the application's retriever beans.
    @Produces
    @Alternative
    @Priority(PRIORITY)
    @ApplicationScoped
    public ContentRetriever trackingContentRetriever(@Any Instance<ContentRetriever> keepAlive,
            Instance<RetrievalTracker> tracker, Instance<CurrentPrincipal> principal, Event<RetrievalRecorded> recorded,
            @ConfigProperty(name = "casehub.langchain4j.tracking.corpus", defaultValue = "default") String corpus) {
        ContentRetriever delegate = resolvedBean(ContentRetriever.class, false, null);
        if (delegate == null) {
            throw new UnsatisfiedResolutionException(
                    "casehub-langchain4j-tracking found no ContentRetriever bean to track — define one, or remove the injection point");
        }
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

    /**
     * The application's augmentor (the bean ArC would inject without this module), wrapped for
     * tracking. Called by the synthetic bean the deployment module registers only when such a bean
     * resolves, or when EasyRAG registers one.
     *
     * @param context the synthetic bean's creational context: a {@code @Dependent} application augmentor
     *        is bound to it, so it is destroyed ({@code @PreDestroy}) with the synthetic bean
     */
    public RetrievalAugmentor trackedApplicationAugmentor(CreationalContext<?> context) {
        RetrievalAugmentor delegate = resolvedBean(RetrievalAugmentor.class, true, context);
        if (delegate == null) {
            throw new UnsatisfiedResolutionException("casehub-langchain4j-tracking found no RetrievalAugmentor bean to track");
        }
        return track(delegate);
    }

    /** Whether the application has a ContentRetriever bean this module wraps, so retrievals can be counted. */
    private boolean retrieversTracked() {
        return beanManager.getBeans(ContentRetriever.class, Any.Literal.INSTANCE).stream()
                .anyMatch(b -> b.getBeanClass() != TrackingBeans.class);
    }

    /** The augmentor wrapped for tracking, or as is when there is no tracker or it is already wrapped. */
    RetrievalAugmentor track(RetrievalAugmentor delegate) {
        if (delegate instanceof TrackingRetrievalAugmentor) {
            return delegate;
        }
        if (trackers.isAmbiguous()) {
            throw new AmbiguousResolutionException("casehub-langchain4j-tracking found several RetrievalTracker beans"
                    + " and cannot tell which one records retrievals — keep one, or mark one as the alternative"
                    + " with the highest priority");
        }
        if (!trackers.isResolvable()) {
            if (reportedNoTracker.compareAndSet(false, true)) {
                LOG.info("casehub-langchain4j-tracking: no RetrievalTracker bean — the retrieval augmentor is not tracked");
            }
            return delegate;
        }
        return new TrackingRetrievalAugmentor(delegate, trackers.get(), corpusName, tenant(principals),
                recordedEvents::fire, retrieversTracked());
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

    /**
     * The bean of {@code type} ArC would inject if this module were absent; {@code null} if there is none.
     * {@code defaultOnly}: no fallback to qualified beans. {@code context}: the creational context
     * a {@code @Dependent} bean is bound to; {@code null} for a fresh, never-released one.
     */
    private <T> T resolvedBean(Class<T> type, boolean defaultOnly, CreationalContext<?> context) {
        List<Bean<?>> candidates = narrow(beanManager.getBeans(type, Any.Literal.INSTANCE).stream()
                .filter(b -> b.getBeanClass() != TrackingBeans.class && !AUGMENTOR_BEAN_NAME.equals(b.getName()))
                .toList());
        if (defaultOnly) {
            // never hand a qualified augmentor to an unqualified lookup
            candidates = candidates.stream()
                    .filter(b -> b.getQualifiers().stream().anyMatch(Default.class::isInstance))
                    .toList();
        }
        if (candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() > 1) {
            throw new AmbiguousResolutionException("casehub-langchain4j-tracking found " + candidates.size() + " "
                    + type.getSimpleName() + " beans and cannot tell which one an unqualified injection means");
        }
        Bean<?> bean = candidates.get(0);
        if (context != null && bean.getScope() == Dependent.class && bean instanceof InjectableBean<?> injectable) {
            return type.cast(dependentOf(injectable, type, context));
        }
        return type.cast(beanManager.getReference(bean, type, beanManager.createCreationalContext(bean)));
    }

    /**
     * A {@code @Dependent} instance bound to {@code parent}, as ArC binds one it injects: created in a
     * child context, which ArC registers with {@code parent}, so it is destroyed (its {@code @PreDestroy}
     * run) when {@code parent} is released.
     */
    private Object dependentOf(InjectableBean<?> bean, Class<?> type, CreationalContext<?> parent) {
        return beanManager.getReference(bean, type, CreationalContextImpl.child(bean, parent));
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
