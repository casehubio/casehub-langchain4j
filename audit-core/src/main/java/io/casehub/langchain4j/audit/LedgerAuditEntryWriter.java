package io.casehub.langchain4j.audit;

import io.casehub.ledger.api.model.AuditRecord;
import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ledger.api.spi.LedgerAppender;
import io.casehub.platform.api.identity.ActorType;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * Writes audit events to the tamper-evident CaseHub ledger through {@link LedgerAppender}.
 *
 * <p>The event {@link AuditEvent#kind() kind} is stored in the record's domain data under
 * {@value #KIND_KEY}. When no tenant can be resolved, or the ledger rejects the write, the event
 * goes to the fallback writer instead — an entry is never written without a tenant.
 */
public class LedgerAuditEntryWriter implements AuditEntryWriter {

    public static final String KIND_KEY = "casehub.lc4j.kind";

    private static final Logger LOG = Logger.getLogger(LedgerAuditEntryWriter.class);

    private final LedgerAppender appender;
    private final Supplier<String> tenancyId;
    private final AuditEntryWriter fallback;

    public LedgerAuditEntryWriter(
            LedgerAppender appender, Supplier<String> tenancyId, AuditEntryWriter fallback) {
        this.appender = Objects.requireNonNull(appender, "appender required");
        this.tenancyId = Objects.requireNonNull(tenancyId, "tenancyId supplier required");
        this.fallback = Objects.requireNonNull(fallback, "fallback required");
    }

    @Override
    public UUID write(AuditEvent event) {
        String tenant;
        try {
            tenant = tenancyId.get();
        } catch (RuntimeException e) {
            LOG.warnf(e, "Audit: tenant lookup failed for %s entry — using fallback writer", event.kind());
            return fallback.write(event);
        }
        if (tenant == null || tenant.isBlank()) {
            LOG.debugf("Audit: no tenant for %s entry — using fallback writer", event.kind());
            return fallback.write(event);
        }
        try {
            UUID entryId = appender.append(toRecord(event), tenant);
            if (entryId != null) {
                return entryId;
            }
            LOG.warnf("Audit: ledger returned no entry ID for %s — using fallback writer", event.kind());
        } catch (RuntimeException e) {
            LOG.warnf(e, "Audit: ledger append failed for %s entry — using fallback writer", event.kind());
        }
        return fallback.write(event);
    }

    private static AuditRecord toRecord(AuditEvent event) {
        Map<String, Object> domainData = new LinkedHashMap<>(event.data());
        domainData.put(KIND_KEY, event.kind());
        return new AuditRecord(
                event.subjectId(),
                event.actorId(),
                ActorType.AGENT,
                null,
                LedgerEntryType.EVENT,
                Instant.now(),
                event.causedByEntryId(),
                null,
                domainData);
    }
}
