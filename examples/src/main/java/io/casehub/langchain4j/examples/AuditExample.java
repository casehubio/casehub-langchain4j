package io.casehub.langchain4j.examples;

import io.casehub.langchain4j.audit.CasehubChatModelListener;
import io.casehub.langchain4j.audit.LoggingAuditEntryWriter;

/**
 * Minimal example: add audit to an existing LangChain4j app.
 * <p>
 * With Quarkus or Spring, this is automatic — just add the dependency.
 * This example shows the pure-Java wiring for non-framework usage.
 *
 * <pre>{@code
 * // 1. Create the audit listener
 * var listener = new CasehubChatModelListener(new LoggingAuditEntryWriter());
 *
 * // 2. Register it on your existing ChatModel
 * ChatModel model = OpenAiChatModel.builder()
 *     .apiKey("...")
 *     .listeners(List.of(listener))  // <-- add this line
 *     .build();
 *
 * // 3. Use your model as normal — every call is now audited
 * model.chat("What is the capital of France?");
 * // Output: AUDIT [completion] model=gpt-4o latency=1234ms tokens=...
 * }</pre>
 */
public class AuditExample {

    public static void main(String[] args) {
        var listener = new CasehubChatModelListener(new LoggingAuditEntryWriter());
        System.out.println("Audit listener created: " + listener);
        System.out.println("Register on your ChatModel via .listeners(List.of(listener))");
    }
}
