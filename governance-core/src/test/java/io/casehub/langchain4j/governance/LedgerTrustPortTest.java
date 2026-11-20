package io.casehub.langchain4j.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.casehub.ledger.api.model.AttestationVerdict;
import io.casehub.ledger.api.model.CapabilityTag;
import io.casehub.ledger.api.model.OutcomeRecord;
import io.casehub.ledger.api.spi.OutcomeRecorder;
import io.casehub.ledger.api.spi.TrustScoreSource;
import io.casehub.platform.api.identity.ActorType;
import java.util.OptionalDouble;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LedgerTrustPortTest {

    private final TrustScoreSource source = mock(TrustScoreSource.class);
    private final OutcomeRecorder recorder = mock(OutcomeRecorder.class);
    private final LedgerTrustPort port = new LedgerTrustPort(source, recorder, 0.7);
    private final UUID subject = UUID.randomUUID();

    private OutcomeRecord recorded() {
        ArgumentCaptor<OutcomeRecord> captor = ArgumentCaptor.forClass(OutcomeRecord.class);
        verify(recorder).record(captor.capture(), eq("acme"));
        return captor.getValue();
    }

    @Test
    void score_delegatesToGlobalScore_withAgentActorId() {
        when(source.globalScore("agent:researcher")).thenReturn(OptionalDouble.of(0.82));

        assertThat(port.score("researcher")).hasValue(0.82);
    }

    @Test
    void recordOutcome_success_recordsSound() {
        port.recordOutcome("researcher", subject, true, "acme");

        OutcomeRecord record = recorded();
        assertThat(record.actorId()).isEqualTo("agent:researcher");
        assertThat(record.subjectId()).isEqualTo(subject);
        assertThat(record.verdict()).isEqualTo(AttestationVerdict.SOUND);
        assertThat(record.confidence()).isEqualTo(0.7);
        assertThat(record.capabilityTag()).isEqualTo(CapabilityTag.GLOBAL);
        assertThat(record.occurredAt()).isNotNull();
        assertThat(record.attestorId()).isEqualTo("system:casehub-langchain4j");
        assertThat(record.attestorType()).isEqualTo(ActorType.SYSTEM);
    }

    @Test
    void recordOutcome_failure_recordsFlagged() {
        port.recordOutcome("researcher", subject, false, "acme");

        assertThat(recorded().verdict()).isEqualTo(AttestationVerdict.FLAGGED);
    }

    @Test
    void confidenceOutsideRange_isRejected() {
        assertThatThrownBy(() -> new LedgerTrustPort(source, recorder, 0.0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("(0.0, 1.0]");
        assertThatThrownBy(() -> new LedgerTrustPort(source, recorder, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
