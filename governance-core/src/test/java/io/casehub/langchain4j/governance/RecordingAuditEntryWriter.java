package io.casehub.langchain4j.governance;

import io.casehub.langchain4j.audit.AuditEntryWriter;
import io.casehub.langchain4j.audit.AuditEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Test double: records every event and hands out predictable, distinct entry IDs. */
public class RecordingAuditEntryWriter implements AuditEntryWriter {

    public final List<AuditEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
    public final List<UUID> ids = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override
    public synchronized UUID write(AuditEvent event) {
        events.add(event);
        UUID id = new UUID(0L, events.size());
        ids.add(id);
        return id;
    }
}
