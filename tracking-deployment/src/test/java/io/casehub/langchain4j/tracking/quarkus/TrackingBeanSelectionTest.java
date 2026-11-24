package io.casehub.langchain4j.tracking.quarkus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.quarkus.arc.InjectableBean;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.spi.Bean;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The delegate-selection rules, without a container. */
class TrackingBeanSelectionTest {

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Bean<?> bean(boolean alternative, int priority, Class<?> beanClass) {
        InjectableBean<?> bean = mock(InjectableBean.class);
        when(bean.isAlternative()).thenReturn(alternative);
        when(bean.getPriority()).thenReturn(priority);
        when(bean.getQualifiers()).thenReturn(java.util.Set.of(Default.Literal.INSTANCE));
        when(((Bean) bean).getBeanClass()).thenReturn(beanClass);
        return bean;
    }

    private static List<String> warningsDuring(Runnable action) {
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger(TrackingBeans.class.getName());
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
    void cragAlternative_winsOverAppBean() {
        Bean<?> app = bean(false, 0, Object.class);
        Bean<?> crag = bean(true, 200, Object.class);

        assertThat(TrackingBeans.narrow(List.of(app, crag))).containsExactly(crag);
    }

    @Test
    void applicationAlternativeAboveUs_excludedAndWarned() {
        Bean<?> app = bean(false, 0, Object.class);
        Bean<?> appAlternative = bean(true, 400, Object.class);

        List<String> warnings = warningsDuring(() -> assertThat(TrackingBeans.narrow(List.of(app, appAlternative)))
                .containsExactly(app));

        assertThat(warnings).anyMatch(m -> m.contains("outranks"));
    }
}
