# CRAG Quality Filter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Decorate the application's `ContentRetriever` with neocortex's `RelevanceEvaluator`: drop INCORRECT content, put CORRECT first, attach grades, publish a quality summary — composing with tenancy as CRAG(Tenancy(app)).

**Architecture:** `crag-core` holds `CragContentRetriever` (pure Java). `crag` (Quarkus) wraps the bean the app would otherwise get via a priority-200 alternative producer; `crag-spring` wraps via an ordered `BeanPostProcessor`. The tenancy modules change so the two decorators compose instead of recursing or nesting backwards.

**Tech Stack:** Java 21, LangChain4j `1.19.0`, `casehub-neocortex-rag-api`, Quarkus `3.39.3`, Spring Boot `4.1.1`, JUnit 5, AssertJ, Mockito.

**Spec:** `docs/superpowers/specs/2026-11-20-crag-filter-design.md`

**Branch / issue:** `feat/crag-filter` (from `feat/phase-1-core`); commits reference `#17`.

## Global Constraints

- Metadata keys: `relevance_grade` (segment metadata, grade name), `source_document_id` (read only); score into `ContentMetadata.RERANKED_SCORE` only when `Float.isFinite(score)`.
- Order of survivors: CORRECT then AMBIGUOUS, original order within each.
- Already graded = every content's segment metadata has `relevance_grade` ≠ `UNGRADED` → returned untouched, no evaluator call, no event.
- Evaluator failure (exception, result size ≠ input size, null entry, `UNGRADED` grade) → return input unfiltered, each content `relevance_grade = UNGRADED`, WARN, quality `evaluated = false`.
- `RetrievalQuality.expandedSearch` is always `false`.
- No evaluator bean → nothing wrapped, one WARN at startup, never a startup failure.
- Quarkus modules inject, never look up; `casehub-neocortex-rag-api` is a required dependency of `crag`.
- Stage files by explicit path only (local tool files are never committed).

## Review Focus

1. **Duplicate chunks** (same text twice) — both graded and kept/dropped independently; evaluator receives both. Test in Task 2 (`duplicateContent_gradedIndependently`).
2. **Content without `source_document_id`** — synthetic `content-<index>` IDs, no `RetrievedChunk` constructor exception. Test in Task 2.
3. **Mixed already-graded and ungraded content** — not "already graded"; the whole list is evaluated. Test in Task 2.
4. **Non-string metadata values** (numbers, UUIDs) — converted to strings for `RetrievedChunk`, original metadata kept on the output content. Test in Task 2.
5. **Tenancy + CRAG in Quarkus with no evaluator** — the app still gets the tenancy wrapper (CRAG producer passes through). Test in Task 3.

---

### Task 1: Make tenancy composable with other decorators

**Files:**
- Modify: `tenancy/src/main/java/io/casehub/langchain4j/tenancy/quarkus/TenancyBeans.java` (`applicationBean` / `narrowLikeArc`)
- Modify: `tenancy-spring/src/main/java/io/casehub/langchain4j/tenancy/spring/TenancyAutoConfiguration.java` (post-processor)
- Test: `tenancy/src/test/java/io/casehub/langchain4j/tenancy/quarkus/HigherPriorityDecoratorTest.java`, `tenancy-spring/src/test/java/io/casehub/langchain4j/tenancy/spring/TenancyAutoConfigurationTest.java`

**Interfaces:**
- Produces: tenancy never selects an `@Alternative` with priority > 100 as its delegate; `TenantIsolatingBeanPostProcessor` implements `Ordered` with `getOrder() == Ordered.HIGHEST_PRECEDENCE + 100` (public constant `TenancyAutoConfiguration.POST_PROCESSOR_ORDER`).

- [ ] **Step 1: Failing tests.**
  - Quarkus `HigherPriorityDecoratorTest` (test profile enabling a test class `OuterDecoratorBeans`: `@Alternative @Priority(200) @ApplicationScoped` producing a `ContentRetriever` that returns one content tagged tenant `a` with text `"outer"`): `tenancyDelegate_isNotTheHigherPriorityDecorator` — select the `ContentRetriever` bean whose bean class is `TenancyBeans` (via `BeanManager.getBeans(ContentRetriever.class, Any.Literal.INSTANCE)`), call `retrieve`; result texts equal `["a's doc"]` (the app bean from `TestStores`), not `["outer"]`.
  - Spring `TenancyAutoConfigurationTest`: `postProcessor_isOrderedEarly` — the post-processor bean is `Ordered` with order `Ordered.HIGHEST_PRECEDENCE + 100`.
- [ ] **Step 2:** `mvn -q test -pl tenancy,tenancy-spring -am` → FAIL (Quarkus returns `"outer"`; Spring not `Ordered`).
- [ ] **Step 3:** In `narrowLikeArc`, before the existing rules, drop candidates that are alternatives with priority > 100 (this producer's own priority). Make the Spring post-processor implement `Ordered`.
- [ ] **Step 4:** Same command → PASS; existing tenancy tests still pass.
- [ ] **Step 5:** Commit `fix(tenancy): compose with higher-priority decorators (Refs #17)`.

---

### Task 2: `crag-core`

**Files:**
- Create: `crag-core/pom.xml` (deps: `langchain4j-core`, `casehub-neocortex-rag-api`, `jboss-logging`), `crag-core/src/main/java/io/casehub/langchain4j/crag/CragContentRetriever.java`, `CragRetrievalQuality.java`
- Modify: root `pom.xml` (`<module>crag-core</module>`, dependencyManagement entry for `casehub-langchain4j-crag-core`; `casehub-neocortex-rag-api` is already managed)
- Test: `crag-core/src/test/java/io/casehub/langchain4j/crag/CragContentRetrieverTest.java`

**Interfaces:**
- Produces:
  - `public CragContentRetriever(ContentRetriever delegate, RelevanceEvaluator evaluator, Consumer<RetrievalQuality> quality)`; `public ContentRetriever delegate()` (for nesting assertions).
  - `public record CragRetrievalQuality(RetrievalQuality quality)` — the event type the wirings publish.
  - `public static final String GRADE_KEY = "relevance_grade"` on `CragContentRetriever`.

- [ ] **Step 1: Failing tests** (fake evaluator: grades by text — `"good…"` CORRECT score 0.9, `"maybe…"` AMBIGUOUS score 0.5, `"bad…"` INCORRECT score 0.1; records calls):
  - `dropsIncorrect_putsCorrectFirst_keepsOrderWithinGrade` — input `[maybe1, good1, bad1, good2, maybe2]` → `[good1, good2, maybe1, maybe2]`.
  - `attachesGradeAndFiniteScore` — `relevance_grade` = `CORRECT`, `RERANKED_SCORE` = 0.9f; with score `NaN` no `RERANKED_SCORE`.
  - `keepsOriginalMetadataAndScore` — input segment metadata and `ContentMetadata.SCORE` survive.
  - `allIncorrect_returnsEmpty_andPublishesQuality` — quality `(3, 0, 0, 3, true, false)`.
  - `emptyResult_noEvaluation_noEvent`.
  - `alreadyGraded_skipsEvaluator_noEvent`.
  - `mixedGradedAndUngraded_evaluatesAll` *(Review Focus 3)*.
  - `duplicateContent_gradedIndependently` *(Review Focus 1)*.
  - `missingSourceDocumentId_usesSyntheticIds` — evaluator receives `content-0`, `content-1` *(Review Focus 2)*.
  - `nonStringMetadata_convertedForEvaluator_originalKept` *(Review Focus 4)*.
  - `evaluatorPassesQueryText` — evaluator receives `query.text()`.
  - `evaluatorThrows_returnsUnfilteredUngraded_evaluatedFalse`; `evaluatorWrongSize_…`; `evaluatorUngradedGrade_…`; `evaluatorNullEntry_…` — all: every input content returned in original order with `relevance_grade = UNGRADED`, quality `evaluated = false`.
  - `qualityConsumerThrows_retrievalStillReturned`.
- [ ] **Step 2:** `mvn -q test -pl crag-core -am` → FAIL (compilation).
- [ ] **Step 3:** Implement per the spec's numbered steps; rebuild contents with `Content.from(TextSegment.from(text, metadataWithGrade), contentMetadata)`.
- [ ] **Step 4:** Same command → PASS.
- [ ] **Step 5:** Commit `feat(crag): CragContentRetriever grades and filters retrieved content (Refs #17)`.

---

### Task 3: `crag` (Quarkus)

**Files:**
- Create: `crag/pom.xml` (deps: `crag-core`, `casehub-neocortex-rag-api` required, `quarkus-arc`; test: `quarkus-junit`, `casehub-langchain4j-tenancy`; jandex plugin), `crag/src/main/java/io/casehub/langchain4j/crag/quarkus/CragBeans.java`
- Modify: root `pom.xml` (`<module>crag</module>`, dependencyManagement)
- Test: `crag/src/test/java/io/casehub/langchain4j/crag/quarkus/{CragBeansTest,CragWithTenancyTest,CragWithoutEvaluatorTest}.java` + test beans

**Interfaces:**
- Consumes: Task 1 tenancy behaviour; Task 2 types.
- Produces: `@Produces @Alternative @Priority(200) @ApplicationScoped ContentRetriever cragContentRetriever(@Any Instance<ContentRetriever> keepAlive, Instance<RelevanceEvaluator> evaluator, Event<CragRetrievalQuality> quality)`; delegate chosen like tenancy (exclude `CragBeans`, ArC rules, ignore alternatives with priority > 200). No evaluator → return the delegate itself, WARN once.

- [ ] **Step 1: Failing tests.**
  - `CragBeansTest` (test app: one app `ContentRetriever` returning good/bad contents; `@Alternative` fake `RelevanceEvaluator` enabled by profile; an observer of `CragRetrievalQuality`): injected retriever drops `bad`, observer got one event.
  - `CragWithTenancyTest` (tenancy on the test classpath, switchable principal, app retriever returning tenant `a` good + tenant `b` good): evaluator receives only tenant `a`'s content; the first retrieval returns (no recursion) — the outer bean unwrapped via `ClientProxy.unwrap` is a `CragContentRetriever` whose `delegate()` unwraps to a `TenantIsolatingContentRetriever`.
  - `CragWithoutEvaluatorTest` (tenancy present, no evaluator): injected retriever unwrapped is a `TenantIsolatingContentRetriever` and filters by tenant *(Review Focus 5)*.
- [ ] **Step 2:** `mvn -q test -pl crag -am` → FAIL.
- [ ] **Step 3:** Implement `CragBeans` (reuse tenancy's selection logic by copying `applicationBean`/`narrowLikeArc` with this producer's class and priority — extraction is optional).
- [ ] **Step 4:** Same command → PASS.
- [ ] **Step 5:** Commit `feat(crag): Quarkus wiring wrapping the resolved ContentRetriever (Refs #17)`.

---

### Task 4: `crag-spring`

**Files:**
- Create: `crag-spring/pom.xml` (deps: `crag-core`, `spring-boot-autoconfigure`, `casehub-neocortex-rag-api` optional; test: `spring-boot-test`, `casehub-langchain4j-tenancy-spring`), `crag-spring/src/main/java/io/casehub/langchain4j/crag/spring/CragAutoConfiguration.java`, `crag-spring/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Modify: root `pom.xml`
- Test: `crag-spring/src/test/java/io/casehub/langchain4j/crag/spring/CragAutoConfigurationTest.java`

**Interfaces:**
- Consumes: `TenancyAutoConfiguration.POST_PROCESSOR_ORDER` (Task 1); Task 2 types.
- Produces: `@AutoConfiguration @ConditionalOnClass({ContentRetriever.class, RelevanceEvaluator.class})` registering a `static` `BeanPostProcessor` implementing `Ordered` with `Ordered.LOWEST_PRECEDENCE - 100`; it resolves `RelevanceEvaluator` lazily via `ObjectProvider` and publishes `CragRetrievalQuality` through `ApplicationEventPublisher`.

- [ ] **Step 1: Failing tests** (`ApplicationContextRunner`): `wrapsRetriever_dropsIncorrect_publishesEvent`; `noEvaluator_leavesRetrieverUnwrapped`; `alreadyCrag_notRewrapped`; `withTenancy_nestsCragOutside` — bean is `CragContentRetriever`, `delegate()` is `TenantIsolatingContentRetriever`, evaluator sees only the current tenant's content.
- [ ] **Step 2:** `mvn -q test -pl crag-spring -am` → FAIL.
- [ ] **Step 3:** Implement the auto-configuration and imports file.
- [ ] **Step 4:** Same command → PASS.
- [ ] **Step 5:** Commit `feat(crag): Spring auto-configuration (Refs #17)`.

---

### Task 5: BOM, docs, full build

**Files:**
- Modify: `bom/pom.xml` (three entries — `BomCoverageTest` enforces it), `docs/guides/consumer-guide.md` (new "crag" module section: what it does, decision table for evaluator failure / already graded / no evaluator, composition with tenancy and hybrid-search, no search expansion, `CragRetrievalQuality` event), `docs/guides/contributor-guide.md` (module table rows), `ARC42STORIES.MD` (C8 ✅, key class, issue #17), `README.md` (module row)

- [ ] **Step 1:** Update the files above. Do not edit `CLAUDE.md` (it carries uncommitted local tool changes); note the missing module-table row in the PR instead.
- [ ] **Step 2:** `mvn clean install` → BUILD SUCCESS, 0 failures.
- [ ] **Step 3:** Commit `docs: CRAG quality filter in BOM, guides and ARC42 (Closes #17)`.
