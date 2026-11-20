package io.casehub.langchain4j.audit.quarkus;

import io.casehub.platform.api.identity.CurrentPrincipal;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Singleton;
import java.util.Set;

/** Enabled only by {@link AuditBeansLedgerTest.LedgerProfile}. Never injected by the test. */
@Alternative
@Singleton
public class TestPrincipal implements CurrentPrincipal {

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
        return "acme";
    }

    @Override
    public boolean isCrossTenantAdmin() {
        return false;
    }
}
