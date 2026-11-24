package io.casehub.langchain4j.tracking.quarkus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.casehub.platform.api.identity.CurrentPrincipal;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

/** The tenant a retrieval is recorded under, without a container. */
class TrackingTenantTest {

    @SuppressWarnings("unchecked")
    private static Instance<CurrentPrincipal> principal(String tenancyId) {
        Instance<CurrentPrincipal> instance = mock(Instance.class);
        if (tenancyId == null) {
            when(instance.isResolvable()).thenReturn(false);
        } else {
            CurrentPrincipal principal = mock(CurrentPrincipal.class);
            when(principal.tenancyId()).thenReturn(tenancyId);
            when(instance.isResolvable()).thenReturn(true);
            when(instance.get()).thenReturn(principal);
        }
        return instance;
    }

    @Test
    void noPrincipal_recordsUnderDefault() {
        assertThat(TrackingBeans.tenant(principal(null)).get()).isEqualTo("default");
    }

    @Test
    void principal_recordsUnderItsTenancy() {
        assertThat(TrackingBeans.tenant(principal("a")).get()).isEqualTo("a");
    }

    @Test
    void blankTenancy_isNotReplacedByAnotherTenant() {
        String tenant = TrackingBeans.tenant(principal(" ")).get();

        assertThat(tenant == null || tenant.isBlank()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void ambiguousPrincipal_failsInsteadOfRecordingUnderDefault() {
        Instance<CurrentPrincipal> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(false);
        when(instance.isAmbiguous()).thenReturn(true);

        assertThatThrownBy(() -> TrackingBeans.tenant(instance))
                .isInstanceOf(jakarta.enterprise.inject.AmbiguousResolutionException.class)
                .hasMessageContaining("several CurrentPrincipal beans");
    }

    @Test
    void principal_isResolvedOnce_notPerRetrieval() {
        Instance<CurrentPrincipal> instance = principal("a");
        java.util.function.Supplier<String> tenant = TrackingBeans.tenant(instance);

        tenant.get();
        tenant.get();

        verify(instance, times(1)).get();
    }
}
