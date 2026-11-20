package io.casehub.langchain4j.audit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BuildSmokeTest {

    @Test
    void loggingWriterCanBeConstructed() {
        assertThat(new LoggingAuditEntryWriter()).isNotNull();
    }
}
