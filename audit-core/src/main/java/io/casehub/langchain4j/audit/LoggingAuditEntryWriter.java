package io.casehub.langchain4j.audit;

import java.util.UUID;
import org.jboss.logging.Logger;

/** Fallback writer that logs audit events when no ledger is available. */
public class LoggingAuditEntryWriter implements AuditEntryWriter {

    private static final Logger LOG = Logger.getLogger(LoggingAuditEntryWriter.class);

    @Override
    public UUID write(AuditEvent event) {
        UUID entryId = UUID.randomUUID();
        LOG.infof("AUDIT [%s] entry=%s actor=%s subject=%s causedBy=%s data=%s",
                event.kind(), entryId, event.actorId(), event.subjectId(),
                event.causedByEntryId(), event.data());
        return entryId;
    }
}
