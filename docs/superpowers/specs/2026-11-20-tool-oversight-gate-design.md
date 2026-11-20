# Tool oversight gate — design

**Date:** 2026-11-20 · **Issue:** casehubio/casehub-langchain4j#10 · **Chapter:** ARC42 C4 (governance)

## Intent

A LangChain4j agent can take consequential actions through its tools — freeze an account, file a
SAR, deploy, spend money. CaseHub's governance promise is that such an action waits for a human
when it is risky. The first attempt put the gate in an `AgentListener`; LangChain4j swallows
listener exceptions (`ListenerAbortContractTest`), so a listener cannot stop anything. This design
puts the gate where an agent actually acts: around its tool calls.

It reuses what CaseHub already has, so an organisation writes its risk rules once:

- **Risk rules** — engine's `ActionRiskClassifier` SPI. `soc`, `life` and `devtown` already
  implement it for case workers; the same classifiers now judge agent tool calls.
- **Approvals** — casehub-work WorkItems, the same mechanism engine uses for its case action gate.

## Decisions taken with the owner (2026-11-20)

| Question | Decision |
|---|---|
| What is gated | **Tool calls**, not agent start. An input guardrail only sees the user message and cannot see actions. |
| A call that needs approval | **Refused, retried after approval.** The tool does not run; the model is told why. After a human approves, the *same* call (same tool, same arguments) runs. Nothing blocks, nothing is executed later on its own. |
| Where approvals live | **casehub-work WorkItems.** No storage of our own. |
| Which action type a tool call carries | **An annotation on the `@Tool` method**, `@GatedAction("account.freeze")`; without it the tool name is the action type. (Tool names cannot contain dots, so existing classifiers such as SOC's `rotate.api.key` need this.) |
| Spring Boot | **Gate wired, refusing gated calls until a `WorkItemApi` bean exists.** casehub-work ships `WorkItemApi` only for Quarkus today; Spring uses an application-provided or future casehub-work bean. |

## Goals

1. Every tool call of a gated agent is classified before it runs.
2. A call classified `GateRequired` runs only after a human approved that exact call.
3. One approval authorises one execution.
4. The agent conversation continues: a refused call returns a tool result the model can explain.
5. Every gate decision is visible in the audit lineage.
6. Works in Quarkus and Spring Boot; attaching it to an agent is one line.

## Non-goals

- Gating agent start or the user's input (an input guardrail would do that; not requested).
- Blocking the thread until a human answers.
- Executing an approved call automatically, or delivering its result into the old conversation.
- Running inside an engine case (engine's own action gate covers case workers).
- Reactive classifiers (`ReactiveActionRiskClassifier`); only the blocking `ActionRiskClassifier`.

## Anti-goals — failures even if everything else works

- A tool runs while its call is pending, rejected, expired, or could not be checked.
- The gate is bypassed silently: misconfiguration must refuse calls or be logged, never pass them.
- An approval for one call authorises a different call (other arguments, other tool, other tenant).
- An approval is used twice.

## Constraints

- LangChain4j `1.19.0` / `langchain4j-agentic` `1.19.0-beta29`. Agent builders accept tools as
  `tools(Map<ToolSpecification, ToolExecutor>)` or a `ToolProvider`; a `ToolExecutor` decides whether
  the tool body runs — this is the enforcement point.
- `casehub-engine-api` (classifier SPI, `PlannedAction`, `ClassificationContext`, `RiskDecision`,
  `ChainedActionRiskClassifier`) and `casehub-work-api` (`WorkItemApi`, `WorkItemCreateRequest`,
  `WorkItemStatus`) stay **optional** dependencies, touched only in holder classes — the rule
  established for the ledger in this repo.
- Tenant comes from `CurrentPrincipal.tenancyId()`, read per call.
- WorkItem status meaning follows engine's `PlanItemCompletionApplier`: `COMPLETED` = approved,
  `REJECTED` = rejected, `EXPIRED` / `CANCELLED` = no decision.

## Approaches considered

| Approach | Verdict |
|---|---|
| `AgentListener.beforeAgentToolExecution` throws | Impossible: LangChain4j catches listener exceptions and runs the tool anyway. |
| Input guardrail | Can refuse a call, but sees only the user message — not the tools the agent will invoke. Rejected by the owner. |
| **Wrap the agent's tool executors; classify with engine's `ActionRiskClassifier`; approvals as casehub-work WorkItems** | **Chosen.** Enforces at the action, reuses existing risk rules and the existing approval inbox. |
| Wrapper + our own approval SPI, app implements storage | Rejected by the owner: nothing works out of the box. |
| Engine's case action gate (`ActionGateScheduler`) | Needs a running case instance; an LC4j agent call is outside a case. |

## Design

### Attaching it

```java
@Inject OversightToolGate gate;

AgenticServices.agentBuilder(Treasurer.class)
    .chatModel(model)
    .tools(gate.forAgent("treasurer").wrap(new PaymentTools()))   // the one line
    .listener(casehubAgentListener)
    .build();
```

`forAgent(name).wrap(Object... toolObjects)` builds the `ToolSpecification`/`ToolExecutor` pairs
from the `@Tool` methods exactly as LangChain4j would, and wraps each executor. An overload accepts
an existing `Map<ToolSpecification, ToolExecutor>`.

### One gated call

```
tool call (tool, arguments) ──► classify ──► Autonomous ─────────────────────────► run tool
                                   │
                                   └─► GateRequired ──► find WorkItem for this call
                                                          ├─ none / expired / cancelled ► create one ─► refuse: pending
                                                          ├─ pending / assigned / in progress ───────► refuse: pending
                                                          ├─ rejected ──────────────────────────────► refuse: rejected
                                                          └─ approved, unused ─► mark used ─► run tool
any failure (no tenant, classifier throws, work API fails, unroutable approvers) ───► refuse: could not check
```

1. **Classify.** `PlannedAction.of(description, actionType, parameters = parsed arguments by their
   parameter names)`, with `actionType` = the `@GatedAction` value of the tool method, or the tool name
   when the method has none, and `description` = `"Agent '<agent>' calls tool '<tool>'"`. A classifier
   that reads an argument (life reads `amount`) needs a tool parameter of that name.
   `ClassificationContext(workerId = agent name, caseId = null, tenancyId, caseDefinitionName = null,
   capabilityName = tool name, bindingName = null)`. The classifiers are the beans qualified
   `@RiskClassifier`, combined with engine's `ChainedActionRiskClassifier`. *(Ruling)* An unqualified
   chain bean from engine runtime is not collected too, or every classifier would run twice. A
   classifier that throws makes the chain return a gate without candidate groups, which is refused.
2. **Identify the call.** Fingerprint = SHA-256 (hex) of tenant, tool name and the arguments as
   canonical JSON (keys sorted, no whitespace). The WorkItem carries the label
   `casehub-lc4j/gate/<fingerprint>` and `callerRef` `casehub-lc4j:gate:<fingerprint>`. The tenant is
   in the fingerprint *and* the WorkItem is created and queried in that tenant.
3. **Look up.** `WorkItemApi.listAll(status = null, …, label = <gate label>, tenancyId, …)`; take the
   newest WorkItem. `DefaultWorkItemApi` ignores the `tenancyId` argument of `listAll` and
   `addLabel`, so tenant isolation of the lookup rests on the tenant being inside the fingerprint.
4. **Create** (`WorkItemCreateRequest`): title = classifier `reason`; payload = JSON with agent, tool,
   arguments, `reversible`; `candidateGroups` from a `StaticSetStrategy`; `expiresAt` = now + `expiresIn`;
   `scope`; `tenancyId`; `createdBy` = `agent:<name>`.
5. **Consume.** Before running an approved call, add the label `casehub-lc4j/gate/used` to the
   WorkItem; if that fails, refuse — never run without marking.

### Status of the found WorkItem *(ruling: engine's own gate mapping, `ActionGateCompletionApplier`)*

| Status | Treated as | Result |
|---|---|---|
| `COMPLETED`, not marked used | approved | mark used, run the tool |
| `COMPLETED`, marked used | spent | create a new request, refuse: pending |
| `PENDING`, `ASSIGNED`, `IN_PROGRESS`, `DELEGATED`, `SUSPENDED` | waiting | refuse: pending |
| `REJECTED`, `CANCELLED`, `OBSOLETE` | rejected | refuse: rejected — no new request, a human decided |
| `EXPIRED`, `FAULTED`, `ESCALATED` | no decision | create a new request, refuse: pending |

### Quorum *(ruling)*

A `GateRequired` with a `quorum` is refused (`could not check`): `WorkItemApi` cannot create or read
an N-of-M group, and one approver must not authorise what the rule says needs several.

### What the model receives when a call is refused

The wrapper returns a tool result (it does not throw), so the agent can tell the user. Exact copy
is the builder's call; it must say the action was **not performed**, why, and the request ID:

> NOT PERFORMED: 'freezeAccount' requires human approval (Account freeze above limit). Approval
> request 3f2a… is pending. Retry the same request after it is approved.

### Audit

Each gate decision writes an `AuditEvent` through the existing `AuditEntryWriter`, chained to the
current agent invocation like `AGENT_TOOL_EXECUTED`: kind `AGENT_TOOL_GATED` with `decision` =
`pending` | `rejected` | `approved` | `unchecked`, `toolName`, `workItemId`, `reason`. Tool arguments
are **not** written to the audit entry (they are in the WorkItem, behind its access control).

*(Ruling)* `CasehubAgentListener` gets a way for subclasses and collaborators to read the current
lineage entry for a `memoryId`; the wrapper reaches the `AgenticScope` through the
`InvocationContext` passed to `ToolExecutor.executeWithContext`. LangChain4j still fires
`afterToolExecution` for a refused call, so the listener marks that `AGENT_TOOL_EXECUTED` entry
`performed = false` when the result is a gate refusal.

### Wiring and degradation

| On the classpath / in the container | Behaviour |
|---|---|
| No `ActionRiskClassifier` bean | Gate is pass-through (every call `Autonomous`); INFO at startup. |
| Classifiers, but no `WorkItemApi` bean (always the case on Spring today) | Autonomous calls run; gated calls are refused (`could not check`); WARN at startup. |
| Classifiers + `WorkItemApi` + `CurrentPrincipal` | Full gate. |
| No `CurrentPrincipal` | Gated calls refused (`could not check`); WARN at startup. |

`OversightToolGate` is produced as a bean in `governance` (Quarkus) and `governance-spring`. Its
ports live in `governance-core` (`RiskPort`, `ApprovalPort`) with engine and work adapters in holder
classes, so `governance-core` tests use fakes.

## Known limits (documented, not solved)

- **Concurrent identical calls** can both see one approval before either marks it used. The wrapper
  serialises calls per fingerprint inside one JVM; across nodes the window remains.
- Approvers are routed only for `StaticSetStrategy` candidate groups. Any other strategy refuses
  the call (`could not check`) rather than creating a WorkItem anyone could approve.
- An approved call must be retried with **identical** arguments; a model that rephrases an argument
  creates a new request.
- Tools added to an agent without `wrap(...)` are not gated.

## Left to the builder

Class and method names beyond `OversightToolGate`, `forAgent`, `wrap`; refusal wording; test layout;
how `@Tool` objects are turned into executors (reuse LangChain4j's own `ToolSpecifications` and
`DefaultToolExecutor`).
