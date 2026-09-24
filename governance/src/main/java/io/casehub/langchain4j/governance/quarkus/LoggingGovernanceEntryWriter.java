package io.casehub.langchain4j.governance.quarkus;

import io.casehub.langchain4j.governance.GovernanceEntryWriter;
import org.jboss.logging.Logger;

public class LoggingGovernanceEntryWriter implements GovernanceEntryWriter {

    private static final Logger LOG = Logger.getLogger(LoggingGovernanceEntryWriter.class);

    @Override
    public void recordInvocationStart(String agentName, Object memoryId) {
        LOG.infof("GOVERNANCE [start] agent=%s memoryId=%s", agentName, memoryId);
    }

    @Override
    public void recordInvocationComplete(String agentName, Object memoryId) {
        LOG.infof("GOVERNANCE [complete] agent=%s memoryId=%s", agentName, memoryId);
    }

    @Override
    public void recordInvocationError(String agentName, Throwable error) {
        LOG.warnf("GOVERNANCE [error] agent=%s error=%s", agentName, error.getMessage());
    }

    @Override
    public void recordToolExecution(String toolName, Object memoryId) {
        LOG.infof("GOVERNANCE [tool] tool=%s memoryId=%s", toolName, memoryId);
    }
}
