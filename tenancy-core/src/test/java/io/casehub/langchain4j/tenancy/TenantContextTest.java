package io.casehub.langchain4j.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TenantContextTest {

    @Test
    void nullPrincipal_isEmpty() {
        assertThat(new TenantContext(null).tenantId()).isEmpty();
    }

    @Test
    void validTenant_isReturned() {
        assertThat(new TenantContext(new MutablePrincipal("acme")).tenantId()).contains("acme");
    }

    @Test
    void nullTenant_throws() {
        assertThatThrownBy(() -> new TenantContext(new MutablePrincipal(null)).tenantId())
                .isInstanceOf(TenantContextMissingException.class);
    }

    @Test
    void blankTenant_throws() {
        assertThatThrownBy(() -> new TenantContext(new MutablePrincipal("  ")).tenantId())
                .isInstanceOf(TenantContextMissingException.class);
    }

    @Test
    void tenantContainingSeparator_throws() {
        assertThatThrownBy(() -> new TenantContext(new MutablePrincipal("a::b")).tenantId())
                .isInstanceOf(TenantContextMissingException.class)
                .hasMessageContaining("::");
    }

    @Test
    void tenantIsReadOnEveryCall() {
        MutablePrincipal principal = new MutablePrincipal("a");
        TenantContext context = new TenantContext(principal);
        assertThat(context.tenantId()).contains("a");
        principal.tenant = "b";
        assertThat(context.tenantId()).contains("b");
    }

    @Test
    void crossTenantAdmin_getsNoBypass() {
        MutablePrincipal principal = new MutablePrincipal("acme");
        principal.crossTenantAdmin = true;
        assertThat(new TenantContext(principal).tenantId()).contains("acme");
    }
}
