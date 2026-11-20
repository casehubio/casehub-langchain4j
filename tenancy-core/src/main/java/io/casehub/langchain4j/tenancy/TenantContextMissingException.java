package io.casehub.langchain4j.tenancy;

/**
 * Thrown when a tenant-isolating decorator is asked to act but the current principal carries no
 * usable tenant. Tenant isolation fails closed: it never falls back to unscoped access.
 */
public class TenantContextMissingException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public TenantContextMissingException(String message) {
        super(message);
    }
}
