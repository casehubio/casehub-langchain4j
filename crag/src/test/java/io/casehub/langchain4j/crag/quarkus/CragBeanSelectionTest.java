package io.casehub.langchain4j.crag.quarkus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.quarkus.arc.InjectableBean;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.literal.NamedLiteral;
import jakarta.enterprise.inject.spi.Bean;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The delegate-selection rules, without a container (no tenancy jar involved). */
class CragBeanSelectionTest {

    private static Bean<?> bean(boolean alternative, int priority, Annotation... qualifiers) {
        InjectableBean<?> bean = mock(InjectableBean.class);
        when(bean.isAlternative()).thenReturn(alternative);
        when(bean.getPriority()).thenReturn(priority);
        when(bean.getQualifiers()).thenReturn(Set.of(qualifiers));
        return bean;
    }

    @Test
    void onlyQualifiedAppBean_isSelected() {
        Bean<?> app = bean(false, 0, NamedLiteral.of("raw"));

        assertThat(CragBeans.narrow(List.of(app))).containsExactly(app);
    }

    @Test
    void tenancyAlternative_winsOverAppBean() {
        Bean<?> app = bean(false, 0, Default.Literal.INSTANCE);
        Bean<?> tenancy = bean(true, 100, Default.Literal.INSTANCE);

        assertThat(CragBeans.narrow(List.of(app, tenancy))).containsExactly(tenancy);
    }

    @Test
    void outrankingAlternative_isNeverTheDelegate() {
        Bean<?> app = bean(false, 0, Default.Literal.INSTANCE);
        Bean<?> outer = bean(true, 300, Default.Literal.INSTANCE);

        assertThat(CragBeans.narrow(List.of(app, outer))).containsExactly(app);
    }
}
