package io.casehub.langchain4j.audit.beans;

import io.casehub.ledger.api.model.AuditRecord;
import io.casehub.ledger.api.model.AttestationVerdict;
import io.casehub.ledger.api.model.OutcomeRecord;
import io.casehub.ledger.api.spi.LedgerAppender;
import io.casehub.ledger.api.spi.OutcomeRecorder;
import io.casehub.ledger.api.spi.TrustScoreSource;
import io.casehub.platform.api.identity.CurrentPrincipal;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * CaseHub beans declared the usual way — class beans that nothing in the application injects —
 * shipped in a dependency jar, so ArC treats them as removable. Wiring tests index this jar and
 * enable these alternatives to prove the modules still find them.
 */
public final class ClassStyleCaseHubBeans {

    private ClassStyleCaseHubBeans() {}

    public static final List<AuditRecord> APPENDED = new CopyOnWriteArrayList<>();
    public static final List<OutcomeRecord> OUTCOMES = new CopyOnWriteArrayList<>();

    @Alternative
    @Singleton
    public static class Appender implements LedgerAppender {
        @Override
        public UUID append(AuditRecord record, String tenancyId) {
            APPENDED.add(record);
            return UUID.randomUUID();
        }
    }

    @Alternative
    @Singleton
    public static class Principal implements CurrentPrincipal {
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

    @Alternative
    @Singleton
    public static class Scores implements TrustScoreSource {
        @Override public OptionalDouble globalScore(String actorId) { return OptionalDouble.of(0.5); }
        @Override public OptionalDouble capabilityScore(String actorId, String tag) { return OptionalDouble.empty(); }
        @Override public OptionalDouble dimensionScore(String actorId, String key) { return OptionalDouble.empty(); }
        @Override public OptionalDouble capabilityDimensionScore(String actorId, String tag, String key) {
            return OptionalDouble.empty();
        }
        @Override public int decisionCount(String actorId, String tag) { return 0; }
        @Override public Map<String, Double> allCapabilityScores(String actorId) { return Map.of(); }
        @Override public Map<String, Double> allDimensionScores(String actorId) { return Map.of(); }
        @Override public Map<String, Double> qualityScores(String actorId, String tag) { return Map.of(); }
    }

    @Alternative
    @Singleton
    public static class Outcomes implements OutcomeRecorder {
        @Override
        public UUID record(OutcomeRecord record) {
            OUTCOMES.add(record);
            return UUID.randomUUID();
        }

        @Override
        public UUID record(OutcomeRecord record, String tenancyId) {
            return record(record);
        }

        @Override
        public void addAttestation(UUID entryId, AttestationVerdict verdict, double confidence, String capabilityTag) {}
    }
}
