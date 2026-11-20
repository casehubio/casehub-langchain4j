package io.casehub.langchain4j.examples;

import io.casehub.langchain4j.audit.AuditEntryWriter;
import io.casehub.langchain4j.audit.AuditEvent;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** An application-defined writer replaces the module's default one — no other wiring needed. */
@Singleton
public class RecordingAuditWriter implements AuditEntryWriter {

    public final List<AuditEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public UUID write(AuditEvent event) {
        events.add(event);
        return UUID.randomUUID();
    }
}
