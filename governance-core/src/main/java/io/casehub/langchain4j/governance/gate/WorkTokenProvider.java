package io.casehub.langchain4j.governance.gate;

import java.util.Optional;

/**
 * The bearer token for calls to a remote casehub-work service on behalf of a tenant. casehub-work takes
 * the tenant from the token, so each tenant needs its own.
 */
@FunctionalInterface
public interface WorkTokenProvider {

    /** The token whose principal belongs to this tenant; empty when this application has none. */
    Optional<String> token(String tenancyId);

    /** One token for one tenant; every other tenant gets none. */
    static WorkTokenProvider single(String tenancyId, String token) {
        if (tenancyId == null || tenancyId.isBlank() || token == null || token.isBlank()) {
            throw new IllegalArgumentException("tenancyId and token are both required");
        }
        return tenant -> tenancyId.equals(tenant) ? Optional.of(token) : Optional.empty();
    }
}
