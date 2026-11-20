package io.casehub.langchain4j.governance.quarkus;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.casehub.ledger.api.spi.OutcomeRecorder;
import io.casehub.ledger.api.spi.TrustScoreSource;
import io.casehub.platform.api.identity.CurrentPrincipal;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.util.OptionalDouble;

/** Enabled only by {@link GovernanceBeansTrustTest.TrustProfile}. */
@Alternative
@ApplicationScoped
public class TestTrustBeans {

    @Produces
    @Singleton
    TrustScoreSource trustScoreSource() {
        TrustScoreSource source = mock(TrustScoreSource.class);
        when(source.globalScore(anyString())).thenReturn(OptionalDouble.of(0.9));
        return source;
    }

    @Produces
    @Singleton
    OutcomeRecorder outcomeRecorder() {
        return mock(OutcomeRecorder.class);
    }

    @Produces
    @Singleton
    CurrentPrincipal currentPrincipal() {
        CurrentPrincipal principal = mock(CurrentPrincipal.class);
        when(principal.tenancyId()).thenReturn("acme");
        return principal;
    }
}
