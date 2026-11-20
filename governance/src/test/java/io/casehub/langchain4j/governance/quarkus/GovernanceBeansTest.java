package io.casehub.langchain4j.governance.quarkus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.casehub.langchain4j.audit.CasehubAgentListener;
import io.casehub.langchain4j.governance.GovernanceAgentListener;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class GovernanceBeansTest {

    @Inject
    CasehubAgentListener listener;

    @Inject
    @Any
    Instance<CasehubAgentListener> allListeners;

    @Test
    void replacesAuditAgentListener() {
        assertThat(listener).isInstanceOf(GovernanceAgentListener.class);
        assertThat(allListeners.stream().count()).isEqualTo(1);
    }

    @Test
    void noLedger_startsAndRecordsLineageOnly() {
        assertThatCode(() -> Invocations.invoke(listener)).doesNotThrowAnyException();
    }
}
