package io.casehub.langchain4j.audit.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.langchain4j.audit.AuditEntryWriter;
import io.casehub.langchain4j.audit.LedgerAuditEntryWriter;
import io.casehub.langchain4j.audit.beans.ClassStyleCaseHubBeans;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Ledger and identity beans declared as plain classes in a dependency jar, injected by nothing
 * else, must still be found; if ArC removed them, audit would silently fall back to logging.
 */
@QuarkusTest
@TestProfile(ClassStyleLedgerDiscoveryTest.Profile.class)
class ClassStyleLedgerDiscoveryTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(ClassStyleCaseHubBeans.Appender.class, ClassStyleCaseHubBeans.Principal.class);
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.index-dependency.acbeans.group-id", "io.casehub.langchain4j",
                    "quarkus.index-dependency.acbeans.artifact-id", "casehub-langchain4j-audit-core",
                    "quarkus.index-dependency.acbeans.classifier", "tests");
        }
    }

    @Inject
    AuditEntryWriter writer;

    @Test
    void classStyleLedgerBeans_areNotRemoved_auditWritesToLedger() {
        assertThat(writer).isInstanceOf(LedgerAuditEntryWriter.class);
    }
}
