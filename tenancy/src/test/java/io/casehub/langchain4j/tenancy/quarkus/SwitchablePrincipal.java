package io.casehub.langchain4j.tenancy.quarkus;

import io.casehub.platform.api.identity.CurrentPrincipal;
import jakarta.inject.Singleton;
import java.util.Set;

@Singleton
public class SwitchablePrincipal implements CurrentPrincipal {

    public volatile String tenant = "a";

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
        return false;
    }
}
