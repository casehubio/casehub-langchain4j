# Oversight gate approvals over casehub-work REST (Spring) — design

**Issue:** casehubio/casehub-langchain4j#15 (upstream in-process gap: casehubio/work#436)

## Problem

The tool oversight gate stores approval requests as casehub-work WorkItems through the
`WorkItemApi` SPI. In Quarkus that bean comes from casehub-work. casehub-work ships no Spring
`WorkItemApi` (casehubio/work#436), so in Spring every gated tool call is refused unless the
application writes its own bean.

casehub-work already exposes the three operations the gate needs over REST
(generated `WorkItemsResource`, base path `/api/work/items`). A Spring application can therefore
reach a separately running casehub-work service now, without waiting for #436.

## Facts that shape the design

- The REST resource takes the tenant from the server-side `CurrentPrincipal`, which casehub
  platform OIDC reads from a JWT claim. **One bearer token means one tenant.** The client cannot
  choose the tenant per request.
- `WorkItemView` carries no `tenancyId`, so the client cannot check returned items for their
  tenant. Isolation rests on choosing the right token for the call's tenant.
- Endpoints:
  - `GET  list-all?label=&offset=&limit=` returns a `WorkItemPage` JSON (`items`, `totalCount`,
    `hasMore`).
  - `POST create` takes a `WorkItemCreateRequest` JSON and returns 201 with a `WorkItemView`.
  - `POST add-label/{id}?path=&appliedBy=` returns 200 with a `WorkItemView`, or 404.

## governance-core

Package `io.casehub.langchain4j.governance.gate`.

- **`WorkItemGateway`** (new, public interface). It holds the three operations the port needs,
  in the port's own types:
  - `List<GateItem> findByLabel(String label, String tenancyId)` returns every page.
  - `GateItem create(WorkItemCreateRequest request, String tenancyId)`.
  - `void addLabel(UUID id, String path, String appliedBy, String tenancyId)`.
- **`GateItem`** (new record). It has only the fields the port reads: `id`, `status`
  (`WorkItemStatus`, null when unknown), `createdBy`, `callerRef`, `createdAt`, `labels`
  (`Set<String>` of label paths) and `candidateGroups` (raw comma string).
- **`WorkItemApiGateway`** wraps `WorkItemApi`. Paging (`PAGE = 50`, stop when `!hasMore` or the
  page is empty) moves here from the port unchanged. It maps `WorkItemView` to `GateItem`. This is
  the existing Quarkus path, with the same behaviour.
- **`WorkItemApprovalPort`** keeps its public constructor `(WorkItemApi)`, which delegates to a
  new constructor `(WorkItemGateway)`. The genuineness filter (callerRef plus an `agent:`
  creator), the newest-wins choice, the status mapping and the payload are unchanged.
- **`HttpWorkItemGateway`** uses the JDK `HttpClient` and Jackson (ISO-8601 `Instant`s through a small
  module of its own, no jsr310 dependency; unknown properties ignored). It stops with
  `WorkItemGatewayException` after 200 pages that still report `hasMore`.
  - Constructor `(URI baseUrl, WorkTokenProvider tokens, Duration timeout)`.
  - Paths are resolved under `<baseUrl>/api/work/items/`.
  - Each request sends `Authorization: Bearer <token>`, where the token comes from
    `tokens.token(tenancyId)`. With no token for the tenant, it throws `IllegalStateException`
    before any request is sent.
  - List responses are parsed into a private DTO (`id`, `status`, `createdBy`, `callerRef`,
    `createdAt`, `labels[].path`, `candidateGroups`). An unknown status string becomes `null`,
    so the request stays WAITING; it never becomes approved.
  - Query values are URL-encoded.
  - Any response other than 2xx (and 201 for create) throws `WorkItemGatewayException`, a
    runtime exception that carries the status code and no body. So do I/O errors and timeouts.
- **`WorkTokenProvider`** (new, public `@FunctionalInterface`): `Optional<String>
  token(String tenancyId)`. It also has a static factory `single(String tenancyId, String token)`
  that answers only for that tenant.

### Failure behaviour

The gate already treats an exception from the approval port as a refusal: the tool call does not
run, and the gate logs and audits it. That is the existing `GatedToolExecutor` behaviour, and
this change keeps it. A test pins it for a 5xx from the gateway.

| Situation | Result |
|---|---|
| no token for the call's tenant | refused, no HTTP request |
| 401 / 403 / 5xx / timeout / connection refused | refused |
| `add-label` 404 (the item was deleted between approval and execution) | refused; the call does not run |
| unknown status string | WAITING |

## governance-spring

Properties under `casehub.langchain4j.governance.work`:

| Property | Meaning | Default |
|---|---|---|
| `url` | base URL of the casehub-work service | — (remote mode off) |
| `token` | bearer token for the single tenant | — |
| `tenancy-id` | the tenant that `token` belongs to | — |
| `timeout` | per-request timeout (`Duration`) | `10s` |

How the approval port is chosen:

1. A `WorkItemApi` bean exists: `WorkItemApprovalPort(WorkItemApi)`, exactly as today.
2. Otherwise, if `…work.url` is set, `WorkItemApprovalPort(new HttpWorkItemGateway(...))`. Tokens
   come from a `WorkTokenProvider` bean if the application defines one (multi-tenant).
   Otherwise they come from `WorkTokenProvider.single(tenancy-id, token)`. With neither a bean
   nor both `token` and `tenancy-id`, the context fails at startup with a message that names the
   three properties. A URL set but auth half-configured is a configuration error, not a silent
   refusal.
3. Otherwise there is no port, and gated calls are refused with the existing WARN, which now
   mentions `…work.url`.

On startup an INFO line names the mode: in-process, or remote with the host only and never the
token. This touches only `WorkSupport` and the `oversightToolGate` javadoc. Remote mode needs
`casehub-work-api` on the classpath, as today: it is optional in governance-core and already
present in the Spring starter.

## Tests

- **governance-core, `HttpWorkItemGatewayTest`** runs against the JDK's
  `com.sun.net.httpserver.HttpServer` on an ephemeral port:
  - `findByLabel` follows `hasMore` across two pages and sends `label`/`offset`/`limit`.
  - The bearer header carries the tenant's token. A tenant without a token throws, and the server
    sees no request.
  - `create` posts JSON with the labels and callerRef, and a 201 maps to `GateItem`.
  - `addLabel` sends the `path` and `appliedBy` query values, encoded.
  - 500 and 404 throw `WorkItemGatewayException`; a response slower than the timeout throws.
  - An unknown status gives `status == null`.
- **`WorkItemApprovalPortTest`**: the existing tests stay green through `(WorkItemApi)`. One
  test runs the port over a fake `WorkItemGateway`.
- **governance-spring, `OversightToolGateAutoConfigurationTest`** (`ApplicationContextRunner`
  plus the embedded server):
  - With a URL, a token and a tenancy, an end-to-end gated call goes: first call creates the
    request and is refused as waiting; the server marks it COMPLETED; the next call runs and
    `add-label used` is sent.
  - A `WorkItemApi` bean wins over a URL.
  - A URL without a token fails the context with the property names.
  - A `WorkTokenProvider` bean is used instead of `token`.
  - A 5xx refuses the call.

## Documentation

- Consumer guide: replace "Spring refuses gated calls" with remote mode (properties, one token
  per tenant, `WorkTokenProvider` for several tenants, and the limitation below).
- `oversightToolGate` javadoc, the tool oversight gate spec, and ARC42 (#15 ✅).
- Close #15 and comment on casehubio/work#436 that in-process Spring is still wanted.

## Limitations / out of scope

- It needs a running casehub-work service. In-process Spring still waits on casehubio/work#436.
- There is no client-side check of an item's tenant, because the view does not expose one.
  Isolation depends on the token-to-tenant mapping being right.
- No retries. A transient failure refuses that call, and the agent may try again.
- The `quarkus` modules are untouched.
