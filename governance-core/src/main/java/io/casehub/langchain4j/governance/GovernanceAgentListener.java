package io.casehub.langchain4j.governance;

import dev.langchain4j.agentic.observability.AfterAgentToolExecution;
import dev.langchain4j.agentic.observability.AgentInvocationError;
import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.agentic.observability.AgentRequest;
import dev.langchain4j.agentic.observability.AgentResponse;
import dev.langchain4j.agentic.observability.BeforeAgentToolExecution;
import org.jboss.logging.Logger;

/**
 * Injects three enterprise concerns into LangChain4j agent patterns:
 * <ol>
 *   <li><b>Causal lineage</b> — every agent invocation produces a CaseLedgerEntry
 *       with causedByEntryId chains</li>
 *   <li><b>Oversight gates</b> — sensitive actions pause for human approval
 *       (requires casehub-engine on classpath)</li>
 *   <li><b>Trust routing</b> — trust scores influence agent selection
 *       (requires casehub-engine on classpath)</li>
 * </ol>
 * <p>
 * Inherits to all sub-agents automatically — a single listener covers
 * the entire agent tree (supervisor → sub-agents → tools).
 */
public class GovernanceAgentListener implements AgentListener {

    private static final Logger LOG = Logger.getLogger(GovernanceAgentListener.class);

    private final GovernanceEntryWriter entryWriter;

    public GovernanceAgentListener(GovernanceEntryWriter entryWriter) {
        this.entryWriter = entryWriter;
    }

    @Override
    public void beforeAgentInvocation(AgentRequest agentRequest) {
        String agentName = agentRequest.agentName();
        Object memoryId = agentRequest.agenticScope().memoryId();

        // TODO: Create in-progress CaseLedgerEntry with causedByEntryId
        // TODO: Check OversightGate — if action requires approval, block
        //       until WorkItem is approved (or timeout)
        // TODO: Check trust scores — if multiple candidate agents,
        //       influence selection based on Bayesian Beta scores

        entryWriter.recordInvocationStart(agentName, memoryId);
        LOG.debugf("Governance: agent invocation starting — agent=%s, memoryId=%s", agentName, memoryId);
    }

    @Override
    public void afterAgentInvocation(AgentResponse agentResponse) {
        String agentName = agentResponse.agentName();
        Object memoryId = agentResponse.agenticScope().memoryId();

        // TODO: Complete CaseLedgerEntry
        // TODO: Update trust score for this agent based on outcome

        entryWriter.recordInvocationComplete(agentName, memoryId);
        LOG.debugf("Governance: agent invocation complete — agent=%s", agentName);
    }

    @Override
    public void onAgentInvocationError(AgentInvocationError error) {
        String agentName = error.agentName();

        // TODO: Record failure in ledger
        // TODO: Decrease trust score for this agent

        entryWriter.recordInvocationError(agentName, error.cause());
        LOG.warnf("Governance: agent invocation error — agent=%s, error=%s",
                agentName, error.cause().getMessage());
    }

    @Override
    public void beforeAgentToolExecution(BeforeAgentToolExecution before) {
        // TODO: Check OversightGate on tool execution if tool is governance-gated
        LOG.debugf("Governance: tool execution starting — tool=%s", before.toolName());
    }

    @Override
    public void afterAgentToolExecution(AfterAgentToolExecution after) {
        // TODO: Record tool call in causal lineage
        entryWriter.recordToolExecution(after.toolName(), after.agenticScope().memoryId());
        LOG.debugf("Governance: tool execution complete — tool=%s", after.toolName());
    }

    @Override
    public boolean inheritedBySubagents() {
        return true;
    }
}
