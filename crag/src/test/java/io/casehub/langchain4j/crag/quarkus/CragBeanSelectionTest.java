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

    /** Stand-in for one of this library's decorating producers (package io.casehub.langchain4j). */
    static final class OuterDecoratorBeans {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Bean<?> beanOf(Class<?> beanClass, int priority) {
        Bean<?> bean = bean(true, priority, Default.Literal.INSTANCE);
        when(((Bean) bean).getBeanClass()).thenReturn(beanClass);
        return bean;
    }

    private static List<String> warningsDuring(Runnable action) {
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger(CragBeans.class.getName());
        List<String> messages = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                messages.add(String.valueOf(record.getMessage()));
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(handler);
        try {
            action.run();
        } finally {
            logger.removeHandler(handler);
        }
        return messages;
    }

    @Test
    void libraryProducerAboveUs_notWarned() {
        Bean<?> app = bean(false, 0, Default.Literal.INSTANCE);
        Bean<?> tracking = beanOf(OuterDecoratorBeans.class, 300);

        List<String> warnings = warningsDuring(() -> assertThat(CragBeans.narrow(List.of(app, tracking))).containsExactly(app));

        assertThat(warnings).noneMatch(m -> m.contains("outranks"));
    }

    @Test
    void applicationAlternativeAboveUs_stillWarned() {
        Bean<?> app = bean(false, 0, Default.Literal.INSTANCE);
        Bean<?> appAlternative = beanOf(Object.class, 300);

        List<String> warnings = warningsDuring(() -> assertThat(CragBeans.narrow(List.of(app, appAlternative))).containsExactly(app));

        assertThat(warnings).anyMatch(m -> m.contains("outranks"));
    }
}
