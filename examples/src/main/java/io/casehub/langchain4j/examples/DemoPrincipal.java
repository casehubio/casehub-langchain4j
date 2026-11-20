package io.casehub.langchain4j.examples;

import io.casehub.platform.api.identity.CurrentPrincipal;
import jakarta.inject.Singleton;
import java.util.Set;

/**
 * Stands in for the real CaseHub identity bean, which resolves the tenant from the request. Here
 * the tenant is switched by hand so the example can play two tenants.
 */
@Singleton
public class DemoPrincipal implements CurrentPrincipal {

    private volatile String tenant = "acme";

    public void switchTo(String tenant) {
        this.tenant = tenant;
    }

    @Override
    public String actorId() {
        return "demo-user";
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
        return false;
    }
}
