package io.casehub.langchain4j.governance.quarkus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import io.casehub.langchain4j.audit.CasehubAgentListener;
import io.casehub.ledger.api.model.OutcomeRecord;
import io.casehub.ledger.api.spi.OutcomeRecorder;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@QuarkusTest
@TestProfile(GovernanceBeansTrustTest.TrustProfile.class)
class GovernanceBeansTrustTest {

    public static class TrustProfile implements QuarkusTestProfile {
        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(TestTrustBeans.class);
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("casehub.langchain4j.governance.outcome-confidence", "0.4");
        }
    }

    @Inject
    CasehubAgentListener listener;

    @Inject
    OutcomeRecorder recorder;

    @Test
    void trustBeans_areUsedWithConfiguredConfidence() {
        Invocations.invoke(listener);

        ArgumentCaptor<OutcomeRecord> captor = ArgumentCaptor.forClass(OutcomeRecord.class);
        verify(recorder).record(captor.capture(), eq("acme"));
        assertThat(captor.getValue().confidence()).isEqualTo(0.4);
        assertThat(captor.getValue().actorId()).isEqualTo("agent:researcher");
    }
}
