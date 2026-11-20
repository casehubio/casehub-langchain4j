package io.casehub.langchain4j.governance.gate;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.util.concurrent.atomic.AtomicInteger;

/** A gated tool (annotated) and an ungated one, counting real executions. */
public class AccountTools {

    final AtomicInteger freezes = new AtomicInteger();
    final AtomicInteger balances = new AtomicInteger();
    volatile RuntimeException freezeFailure;

    @Tool("Freezes an account")
    @GatedAction("account.freeze")
    public String freezeAccount(@P("accountId") String accountId) {
        freezes.incrementAndGet();
        if (freezeFailure != null) {
            throw freezeFailure;
        }
        return "frozen " + accountId;
    }

    @Tool("Reads an account balance")
    public String balance(@P("accountId") String accountId) {
        balances.incrementAndGet();
        return "balance of " + accountId + " is 100";
    }
}
