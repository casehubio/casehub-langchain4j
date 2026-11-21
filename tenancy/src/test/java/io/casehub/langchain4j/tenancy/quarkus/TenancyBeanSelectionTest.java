package io.casehub.langchain4j.tenancy.quarkus;

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

/** The delegate-selection rules, without a container. */
class TenancyBeanSelectionTest {

    private static Bean<?> bean(boolean alternative, int priority, Annotation... qualifiers) {
        InjectableBean<?> bean = mock(InjectableBean.class);
        when(bean.isAlternative()).thenReturn(alternative);
        when(bean.getPriority()).thenReturn(priority);
        when(bean.getQualifiers()).thenReturn(Set.of(qualifiers));
        return bean;
    }

    @Test
    void onlyQualifiedAppBean_withOutrankingDecorator_selectsTheAppBean() {
        Bean<?> app = bean(false, 0, NamedLiteral.of("raw"));
        Bean<?> crag = bean(true, 200, Default.Literal.INSTANCE);

        assertThat(TenancyBeans.narrowLikeArc(List.of(app, crag))).containsExactly(app);
    }

    @Test
    void defaultAppBean_withOutrankingDecorator_selectsTheAppBean() {
        Bean<?> app = bean(false, 0, Default.Literal.INSTANCE);
        Bean<?> crag = bean(true, 200, Default.Literal.INSTANCE);

        assertThat(TenancyBeans.narrowLikeArc(List.of(app, crag))).containsExactly(app);
    }
}
