package io.casehub.langchain4j.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.casehub.ledger.api.model.AuditRecord;
import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ledger.api.spi.LedgerAppender;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LedgerAuditEntryWriterTest {

    private final LedgerAppender appender = mock(LedgerAppender.class);
    private final RecordingAuditEntryWriter fallback = new RecordingAuditEntryWriter();
    private final AtomicReference<String> tenancy = new AtomicReference<>("acme");
    private final LedgerAuditEntryWriter writer = new LedgerAuditEntryWriter(appender, tenancy::get, fallback);

    private final UUID subject = UUID.randomUUID();
    private final UUID cause = UUID.randomUUID();
    private final AuditEvent event = new AuditEvent(
            AuditEvent.AI_CHAT_COMPLETION, "llm:gpt-test", subject, cause, Map.of("model", "gpt-test"));

    @Test
    void write_appendsAuditRecordWithTenancy() {
        UUID ledgerId = UUID.randomUUID();
        when(appender.append(any(), eq("acme"))).thenReturn(ledgerId);

        UUID returned = writer.write(event);

        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(appender).append(captor.capture(), eq("acme"));
        AuditRecord record = captor.getValue();
        assertThat(returned).isEqualTo(ledgerId);
        assertThat(record.subjectId()).isEqualTo(subject);
        assertThat(record.actorId()).isEqualTo("llm:gpt-test");
        assertThat(record.causedByEntryId()).isEqualTo(cause);
        assertThat(record.entryType()).isEqualTo(LedgerEntryType.EVENT);
        assertThat(record.occurredAt()).isNotNull();
        assertThat(record.domainData()).containsOnly(
                Map.entry("model", "gpt-test"),
                Map.entry("casehub.lc4j.kind", "AI_CHAT_COMPLETION"));
        assertThat(fallback.events).isEmpty();
    }

    @Test
    void write_blankTenancy_usesFallback() {
        tenancy.set(null);
        UUID first = writer.write(event);
        tenancy.set(" ");
        UUID second = writer.write(event);

        verify(appender, never()).append(any(), any());
        assertThat(fallback.events).containsExactly(event, event);
        assertThat(first).isEqualTo(fallback.ids.get(0));
        assertThat(second).isEqualTo(fallback.ids.get(1));
    }

    @Test
    void write_appenderThrows_usesFallback() {
        when(appender.append(any(), any())).thenThrow(new IllegalStateException("ledger down"));

        UUID returned = writer.write(event);

        assertThat(fallback.events).containsExactly(event);
        assertThat(returned).isEqualTo(fallback.ids.get(0));
    }
}
