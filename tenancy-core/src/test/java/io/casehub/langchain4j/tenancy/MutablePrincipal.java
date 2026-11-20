package io.casehub.langchain4j.tenancy;

import io.casehub.platform.api.identity.CurrentPrincipal;
import java.util.Set;

/** Test double: a principal whose tenant can be switched mid-test. */
class MutablePrincipal implements CurrentPrincipal {

    String tenant;
    boolean crossTenantAdmin;

    MutablePrincipal(String tenant) {
        this.tenant = tenant;
    }

    @Override
    public String actorId() {
        return "tester";
    }

    @Override
    public Set<String> groups() {
        return Set.of();
    }

    @Override
    public String tenancyId() {
        return tenant;
    }

    @Override
    public boolean isCrossTenantAdmin() {
        return crossTenantAdmin;
    }
}
