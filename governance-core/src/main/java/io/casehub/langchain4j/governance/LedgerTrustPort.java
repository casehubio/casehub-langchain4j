package io.casehub.langchain4j.governance;

import io.casehub.ledger.api.model.AttestationVerdict;
import io.casehub.ledger.api.model.CapabilityTag;
import io.casehub.ledger.api.model.OutcomeRecord;
import io.casehub.ledger.api.spi.OutcomeRecorder;
import io.casehub.ledger.api.spi.TrustScoreSource;
import io.casehub.platform.api.identity.ActorType;
import java.time.Instant;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.UUID;

/**
 * {@link TrustPort} backed by the CaseHub ledger: scores come from {@link TrustScoreSource}, and
 * outcomes are recorded through {@link OutcomeRecorder} as {@code SOUND} (completed) or
 * {@code FLAGGED} (failed) attestations on the global capability.
 *
 * <p>Agents are identified as {@code agent:<name>} — the same actor ID the audit entries use.
 */
public class LedgerTrustPort implements TrustPort {

    /**
     * The attestor named on every outcome. Set explicitly so recording does not depend on the
     * ledger's {@code casehub.ledger.outcome.default-attestor-id} setting.
     */
    public static final String ATTESTOR_ID = "system:casehub-langchain4j";

    /** Default weight of one agent invocation as trust evidence. */
    public static final double DEFAULT_CONFIDENCE = 0.7;

    private final TrustScoreSource source;
    private final OutcomeRecorder recorder;
    private final double confidence;

    /** @param confidence weight of one invocation as evidence, in {@code (0.0, 1.0]} */
    public LedgerTrustPort(TrustScoreSource source, OutcomeRecorder recorder, double confidence) {
        this.source = Objects.requireNonNull(source, "source required");
        this.recorder = Objects.requireNonNull(recorder, "recorder required");
        if (!(confidence > 0.0 && confidence <= 1.0)) {
            throw new IllegalArgumentException("outcome confidence must be in (0.0, 1.0] — got " + confidence);
        }
        this.confidence = confidence;
    }

    @Override
    public OptionalDouble score(String agentName) {
        return source.globalScore(actorId(agentName));
    }

    @Override
    public void recordOutcome(String agentName, UUID subjectId, boolean success, String tenancyId) {
        AttestationVerdict verdict = success ? AttestationVerdict.SOUND : AttestationVerdict.FLAGGED;
        recorder.record(
                new OutcomeRecord(
                        actorId(agentName), subjectId, verdict, confidence, CapabilityTag.GLOBAL,
                        ActorType.AGENT, null, Instant.now(), ATTESTOR_ID, ActorType.SYSTEM, null, null),
                tenancyId);
    }

    private static String actorId(String agentName) {
        return "agent:" + (agentName == null || agentName.isBlank() ? "unknown" : agentName);
    }
}
