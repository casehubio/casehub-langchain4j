package io.casehub.langchain4j.tenancy;

import org.jboss.logging.Logger;

/** Shared startup warnings for the framework wiring modules. */
public final class TenancyWarnings {

    private static final Logger LOG = Logger.getLogger(TenancyWarnings.class);

    private TenancyWarnings() {}

    /**
     * Logs, at WARN, that a store is being wrapped without a {@code CurrentPrincipal}: the module is
     * on the classpath, but every call will pass through unscoped.
     */
    public static void noPrincipal(String storeType) {
        LOG.warnf("casehub-langchain4j-tenancy: no CurrentPrincipal bean is available, so the %s is NOT "
                + "tenant-isolated — every call passes through unscoped. Provide a CurrentPrincipal bean "
                + "(CaseHub platform identity) to enable isolation.", storeType);
    }
}
