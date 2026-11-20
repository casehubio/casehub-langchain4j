package io.casehub.langchain4j.tenancy;

import io.casehub.platform.api.identity.CurrentPrincipal;
import java.util.Optional;

/**
 * The single place that decides which tenant a tenant-isolating decorator acts for.
 *
 * <ul>
 *   <li>No {@link CurrentPrincipal} at all (CaseHub identity is not wired in) — empty: the
 *       decorators pass through unchanged.
 *   <li>A principal whose tenant is null, blank, or contains {@value #SEPARATOR} —
 *       {@link TenantContextMissingException}: isolation fails closed.
 *   <li>Otherwise — that tenant. {@code isCrossTenantAdmin()} grants no bypass.
 * </ul>
 */
public final class TenantContext {

    /** Metadata key that tags stored content with its owning tenant. */
    public static final String METADATA_KEY = "casehub_tenant_id";

    /** Separator between tenant and memory ID in a tenant-scoped memory ID. */
    public static final String SEPARATOR = "::";

    private final CurrentPrincipal principal;

    public TenantContext(CurrentPrincipal principalOrNull) {
        this.principal = principalOrNull;
    }

    /** Resolves the tenant for the current call; read from the principal every time. */
    public Optional<String> tenantId() {
        if (principal == null) {
            return Optional.empty();
        }
        String tenant = principal.tenancyId();
        if (tenant == null || tenant.isBlank()) {
            throw new TenantContextMissingException(
                    "CurrentPrincipal has no tenancyId — refusing unscoped access to a tenant-isolated store");
        }
        if (tenant.contains(SEPARATOR)) {
            throw new TenantContextMissingException(
                    "tenancyId must not contain '" + SEPARATOR + "' — it is the tenant/memory-ID separator");
        }
        return Optional.of(tenant);
    }
}
