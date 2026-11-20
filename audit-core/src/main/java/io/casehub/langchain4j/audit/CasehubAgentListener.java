package io.casehub.langchain4j.audit;

import dev.langchain4j.agentic.observability.AfterAgentToolExecution;
import dev.langchain4j.agentic.observability.AgentInvocationError;
import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.agentic.observability.AgentRequest;
import dev.langchain4j.agentic.observability.AgentResponse;
import dev.langchain4j.agentic.scope.AgenticScope;
import java.nio.charset.StandardCharsets;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.jboss.logging.Logger;

/**
 * Records the causal lineage of an agent tree: every invocation writes a STARTED entry and a
 * COMPLETED or FAILED entry, every tool call writes a TOOL_EXECUTED entry, and each entry points
 * at the entry that caused it.
 *
 * <p>Lineage is tracked as a stack of open invocations per {@code memoryId}, held inside this
 * listener — nothing is written into the {@link AgenticScope} state, which is user-visible and
 * may be persisted. A conversation's entry is dropped as soon as no invocation is open, and on
 * suspension or scope destruction, so the listener holds no state between invocations. The stack model assumes invocations sharing one {@code memoryId} nest; agents
 * running <em>in parallel</em> under the same {@code memoryId} may be chained to a sibling rather
 * than to their true parent.
 *
 * <p>Attach it to an agent (it is inherited by all sub-agents); LangChain4j has no global agent
 * listener registry. A failing {@link AuditEntryWriter} never breaks the agent invocation.
 */
public class CasehubAgentListener implements AgentListener {

    private static final Logger LOG = Logger.getLogger(CasehubAgentListener.class);
    private static final Object NO_MEMORY_ID = new Object();

    private final AuditEntryWriter writer;
    private final Map<Object, Deque<Frame>> openInvocations = new ConcurrentHashMap<>();

    /** One open invocation; {@code entryId} is null when its STARTED entry could not be written. */
    private record Frame(UUID entryId, long startNanos) {}

    public CasehubAgentListener(AuditEntryWriter writer) {
        this.writer = writer;
    }

    @Override
    public void beforeAgentInvocation(AgentRequest request) {
        Object memoryId = memoryId(request.agenticScope());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agentName", request.agentName());
        try {
            data.putAll(startData(request));
        } catch (RuntimeException e) {
            LOG.warnf(e, "Audit: startData failed for agent %s", request.agentName());
        }
        UUID entryId = write(new AuditEvent(
                AuditEvent.AGENT_INVOCATION_STARTED,
                actorId(request.agentName()),
                subjectIdFor(memoryId),
                openEntryId(memoryId),
                data));
        Frame frame = new Frame(entryId, System.nanoTime());
        openInvocations.compute(key(memoryId), (k, stack) -> {
            Deque<Frame> frames = stack == null ? new ConcurrentLinkedDeque<>() : stack;
            frames.push(frame);
            return frames;
        });
    }

    @Override
    public void afterAgentInvocation(AgentResponse response) {
        Object memoryId = memoryId(response.agenticScope());
        Frame frame = pop(memoryId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agentName", response.agentName());
        if (frame != null) {
            data.put("durationMs", Math.max(0L, (System.nanoTime() - frame.startNanos()) / 1_000_000));
        }
        write(new AuditEvent(
                AuditEvent.AGENT_INVOCATION_COMPLETED,
                actorId(response.agentName()),
                subjectIdFor(memoryId),
                frame == null ? null : frame.entryId(),
                data));
    }

    @Override
    public void onAgentInvocationError(AgentInvocationError error) {
        recordFailure(error.agentName(), memoryId(error.agenticScope()), error.error());
    }

    @Override
    public void afterAgentToolExecution(AfterAgentToolExecution after) {
        Object memoryId = memoryId(after.agenticScope());
        String agentName = after.agentInstance() == null ? null : after.agentInstance().name();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("toolName", after.toolExecution().request().name());
        if (!toolPerformed(after)) {
            data.put("performed", false);
        }
        write(new AuditEvent(
                AuditEvent.AGENT_TOOL_EXECUTED,
                actorId(agentName),
                subjectIdFor(memoryId),
                openEntryId(memoryId),
                data));
    }

    /**
     * A suspended invocation (waiting for a human, for example) ends without an after or error
     * callback. Its open frames are dropped so the resumed or next invocation starts a fresh chain
     * instead of being parented to a stale entry.
     */
    @Override
    public void onAgenticSystemSuspended(AgenticScope agenticScope) {
        openInvocations.remove(key(memoryId(agenticScope)));
    }

    @Override
    public void beforeAgenticScopeDestroyed(AgenticScope agenticScope) {
        openInvocations.remove(key(memoryId(agenticScope)));
    }

    @Override
    public boolean inheritedBySubagents() {
        return true;
    }

    /**
     * Extra payload for the STARTED entry. Subclasses add governance facts here.
     *
     * @return entries merged into the STARTED event's data; never {@code null}
     */
    protected Map<String, Object> startData(AgentRequest request) {
        return Map.of();
    }

    /**
     * Whether the tool call actually ran. LangChain4j reports a tool execution even when a wrapper
     * refused it; subclasses that know about such refusals return {@code false}, and the
     * {@code AGENT_TOOL_EXECUTED} entry is marked {@code performed = false}.
     */
    protected boolean toolPerformed(AfterAgentToolExecution after) {
        return true;
    }

    /** The ledger subject for one agent conversation: stable for a given {@code memoryId}. */
    protected UUID subjectIdFor(Object memoryId) {
        return UUID.nameUUIDFromBytes(("lc4j-agent:" + memoryId).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Closes the innermost open invocation for {@code memoryId} with a FAILED entry. Subclasses
     * call this when they abort an invocation they have already let start.
     */
    protected void recordFailure(String agentName, Object memoryId, Throwable error) {
        Frame frame = pop(memoryId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agentName", agentName);
        if (error != null) {
            // The class only: provider and parsing error messages often quote prompt or model text.
            data.put("errorClass", error.getClass().getName());
        }
        write(new AuditEvent(
                AuditEvent.AGENT_INVOCATION_FAILED,
                actorId(agentName),
                subjectIdFor(memoryId),
                frame == null ? null : frame.entryId(),
                data));
    }

    /** The actor ID used for an agent in audit entries and trust records. */
    protected static String actorId(String agentName) {
        return "agent:" + (agentName == null || agentName.isBlank() ? "unknown" : agentName);
    }

    /** Number of conversations with open invocations; for tests. */
    int trackedConversations() {
        return openInvocations.size();
    }

    private UUID write(AuditEvent event) {
        try {
            return writer.write(event);
        } catch (RuntimeException e) {
            LOG.warnf(e, "Audit: failed to write %s entry — the agent invocation is unaffected", event.kind());
            return null;
        }
    }

    /** Pops the innermost open frame; the conversation's entry is dropped once nothing is open. */
    private Frame pop(Object memoryId) {
        Frame[] popped = new Frame[1];
        openInvocations.computeIfPresent(key(memoryId), (k, stack) -> {
            popped[0] = stack.poll();
            return stack.isEmpty() ? null : stack;
        });
        return popped[0];
    }

    /**
     * The entry ID of the innermost invocation still open for {@code memoryId} — what a new entry
     * about that conversation should be caused by. Empty when nothing is open, or when the open
     * invocation's STARTED entry could not be written.
     */
    public Optional<UUID> currentEntryId(Object memoryId) {
        return Optional.ofNullable(openEntryId(memoryId));
    }

    private UUID openEntryId(Object memoryId) {
        Deque<Frame> stack = openInvocations.get(key(memoryId));
        Frame current = stack == null ? null : stack.peek();
        return current == null ? null : current.entryId();
    }

    private static Object memoryId(AgenticScope scope) {
        return scope == null ? null : scope.memoryId();
    }

    private static Object key(Object memoryId) {
        return memoryId == null ? NO_MEMORY_ID : memoryId;
    }
}
