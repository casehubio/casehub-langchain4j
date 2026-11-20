package io.casehub.langchain4j.audit.quarkus;

import io.casehub.ledger.api.model.AuditRecord;
import io.casehub.ledger.api.spi.LedgerAppender;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** Enabled only by {@link AuditBeansLedgerTest.LedgerProfile}. */
@Alternative
@Singleton
public class TestLedgerAppender implements LedgerAppender {

    public final List<String> tenancies = new CopyOnWriteArrayList<>();
    public final List<AuditRecord> records = new CopyOnWriteArrayList<>();

    @Override
    public UUID append(AuditRecord record, String tenancyId) {
        records.add(record);
        tenancies.add(tenancyId);
        return UUID.randomUUID();
    }
}
