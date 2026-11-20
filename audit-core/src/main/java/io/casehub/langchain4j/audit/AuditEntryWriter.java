package io.casehub.langchain4j.audit;

import java.util.UUID;

/**
 * SPI for persisting audit events. Implementations write to the CaseHub ledger
 * (when it is on the classpath) or fall back to logging.
 */
public interface AuditEntryWriter {

    /**
     * Persists the event.
     *
     * @return the ID of the written entry, never {@code null}; later events reference it as
     *     {@link AuditEvent#causedByEntryId()}
     */
    UUID write(AuditEvent event);
}
