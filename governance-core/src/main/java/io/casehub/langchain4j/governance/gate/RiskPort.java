package io.casehub.langchain4j.governance.gate;

/** Decides whether a tool call may run on its own or needs a human's approval. */
public interface RiskPort {

    RiskVerdict classify(GatedToolCall call);
}
