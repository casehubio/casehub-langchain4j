package io.casehub.langchain4j.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Test double: records every event and hands out predictable, distinct entry IDs. */
class RecordingAuditEntryWriter implements AuditEntryWriter {

    final List<AuditEvent> events = new ArrayList<>();
    final List<UUID> ids = new ArrayList<>();
    RuntimeException failure;

    @Override
    public UUID write(AuditEvent event) {
        if (failure != null) {
            throw failure;
        }
        events.add(event);
        UUID id = new UUID(0L, events.size());
        ids.add(id);
        return id;
    }
}
