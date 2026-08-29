# Module Audit - Deep-Module Lens

Using the vocabulary from `/codebase-design`: **Module** (interface + implementation), **Interface** (caller surface), **Implementation** (internal code), **Depth** (leverage per unit of interface), **Seam** (location of the interface), **Adapter** (what fills the seam), **Leverage** (caller benefit), **Locality** (maintainer benefit).

Audit snapshot: production code at commit `74478f3` (2026-08-29 review). Findings are a design backlog, not claims that every opportunity has been implemented.

Audited modules:

- `VideoEvidenceRetrievalService`
- `AnalysisDispatchService`
- `ChunkUploadService`

## Summary

| Module                 | Interface | Implementation | Verdict                                      |
| ---------------------- | --------- | -------------- | -------------------------------------------- |
| VideoEvidenceRetrieval | 3 methods | ~200 lines     | **Deep** - one chunk-ownership leak          |
| AnalysisDispatch       | 5 + enum  | ~130 lines     | **Moderate** - dead overload, nullable param |
| ChunkUpload            | 4 methods | ~180 lines     | **Deep** - one redundant param               |

All three pass the **deletion test**: deleting them would scatter complexity back into callers. None is shallow. The opportunities below are tightening, not rescue.

---

## VideoEvidenceRetrievalService

### Interface

```java
List<VideoSegment>   retrieve(Long mediaId, String goal,    List<VideoChunk> chunks)
List<VideoEvidenceHit> search(Long mediaId, String query,   List<VideoChunk> chunks)
void                 index(Long mediaId,                     List<VideoChunk> chunks)
```

### Implementation

`rank()` orchestrates: LLM intent extraction -> embedding -> Qdrant search (with fallback to in-memory cosine) -> hybrid chunk scoring (semantic 0.6 + keyword 0.25 + visual 0.15) -> segment-level re-scoring -> top-K + max-hits truncation. ~200 lines of non-trivial scoring, fallback, and OCR-channel logic.

### Depth verdict: **Deep**

The two query methods take three params and `index` takes two. Caller learns the signatures once and gets hybrid retrieval, graceful degradation, OCR channel, telemetry, and LLM-driven query rewriting. The **Seam** sits at the service boundary; callers cannot see Qdrant, DeepSeek, or the embedding service. Two **Adapters** could swap (`QdrantVectorStore`, `DeepSeekUtils`) without callers noticing. **Leverage** is high - one caller (`LongVideoContextService`) reaches all three methods. **Locality** is high - scoring tweaks and fallback policy live here, not in callers.

### Deepening opportunities

1. **The `chunks` parameter leaks ownership.** Callers pass `List<VideoChunk>` to `retrieve`, `search`, _and_ `index`. The service writes to Qdrant via `index()` but still asks for chunks back on `retrieve()`/`search()`. The service should own the chunk lifecycle: `index(mediaId, chunks)` stores them (in-memory or Qdrant-backed), `retrieve(mediaId, goal)` and `search(mediaId, query)` load them internally. Removing `chunks` from two methods shrinks the interface and centralizes chunk storage - one place to add caching, one place to evict.

2. **`retrieve` vs `search` naming is ambiguous.** Same params, different return types, different limits (TOP_K=3 chunks for `retrieve`, MAX_USER_HITS=8 hits for `search`). The semantic difference is _consumer_: `retrieve` feeds the AgentLoop, `search` answers a user query. Rename to `retrieveForAgent` / `searchForUser`, or split into two Adapters behind one Interface - the latter only if the implementations diverge.

3. **`index()` swallows exceptions silently.** Caller cannot tell whether Qdrant accepted the upsert. The graceful-degradation policy is correct (don't block analysis on infra failure), but the interface lies: the method name promises indexing. Either rename to `tryIndex` (honest) or return `boolean`/enum so callers can decide whether to act on degradation.

### What to say in an interview

> "This is a deep module - three methods, ~200 lines of hybrid retrieval behind them. The seam sits at the service boundary; Qdrant and the LLM are adapters behind it. The graceful degradation is invisible to callers, which is the right depth: the AgentLoop doesn't need to know Qdrant was down. The one thing I'd tighten is chunk ownership - the service writes chunks via `index()` but still asks callers to pass them back on `retrieve()`. That's a leak I'd fix by having the service own the chunk store."

---

## AnalysisDispatchService

### Interface

```java
SubmissionResult submit(MediaFile mediaFile, String goal, AgentFeedback revision)
SubmissionResult submit(MediaFile mediaFile, String goal, AgentFeedback revision, AnalysisMode mode)
boolean           isActive(Long mediaId, String goal)
boolean           isActive(Long mediaId, String goal, AnalysisMode mode)
void              requireAiQuota(Long userId)
enum SubmissionResult { ACCEPTED, RATE_LIMITED, DUPLICATE, FAILED }
```

### Implementation

`submit()` orchestrates Redis `SETNX` dedup with 6h TTL -> Redisson token-bucket quota (user 5/min, global 30/min) -> staged revision -> RocketMQ dispatch -> stage event publish -> rollback on failure. `requireAiQuota()` applies the same cost guard to interactive follow-up and evidence search. ~130 lines.

### Depth verdict: **Moderate**

Five public methods (two overload pairs and one interactive quota guard) plus an enum. The Implementation folds several concerns into one call, which gives **Leverage** - callers don't queue dedup, quota, and dispatch separately. But the Interface carries two shallow-module smells:

### Deepening opportunities

1. **The 3-arg `submit` overload is dead.** Grep shows no caller - both call sites in `AnalysisController` pass `AnalysisMode`. The overload exists "for backward compat" per its comment, but the backward-compat caller is gone. Delete it. A method that exists for callers that don't exist is the textbook shallow-module smell - the interface is growing without leverage.

2. **`AgentFeedback revision` is nullable.** Null means START_ANALYSIS; non-null means REVISE_ANALYSIS. The interface encodes a binary choice via nullability, which is fragile - a caller can forget to pass revision and silently start a new analysis instead of revising. Split into `submitNew(mediaFile, goal, mode)` and `submitRevision(mediaFile, goal, revision, mode)`. Each method has one contract; no nullable trap.

3. **`MediaFile` parameter is heavier than needed.** `submit` takes `MediaFile` but only reads `getId()` and `getUserId()`. The current signature forces callers to load the full entity first. Could be `submit(Long mediaId, Long userId, ...)`. Minor - if the controller already has MediaFile loaded (it does, for ownership checks), passing it is convenient. Leave unless callers are forced to load it just for this call.

4. **`isActive` overload duplication.** The mode-overload checks both `contentHash(mediaId)` and `"media-" + mediaId` keys. The 2-arg overload delegates with `GENERAL`. Could collapse to one method that always checks both keys - the mode parameter only affects the goalDigest, not the contentHash form. Minor.

5. **`AiService.stageRevision` / `cancelStagedRevision` side effects.** `submit()` does more than its name suggests: it stages revision state in `AiService` and rolls it back on failure. The Interface name "submit" promises dispatch, not revision staging. Either rename to reflect the side effect, or extract a `RevisionStagingService` seam so `submit` is pure dispatch. The current shape is fine if you accept that "submit" means "submit-and-stage-revision", but a future maintainer will be surprised.

6. **Quota ownership is broader than dispatch.** `requireAiQuota()` is used by follow-up and evidence-search endpoints, so the module owns a cross-cutting AI cost policy in addition to dispatch. This reuse is practical, but an `AiQuotaService` would become a clearer seam if more interactive AI endpoints are added.

### What to say in an interview

> "Moderate depth - five methods, an enum, and about 130 lines of orchestration. The leverage is real: callers get dedup, quota, MQ dispatch, and stage events in one call, while interactive endpoints reuse the same cost guard. The smells are an unused backward-compat overload and a nullable `revision` parameter that encodes start-vs-revise. I'd drop the dead overload and split `submit` into `submitNew` and `submitRevision` so the choice is in the method name, not in nullability."

---

## ChunkUploadService

### Interface

```java
String        initialize(String filename, int totalChunks, Long userId)
Set<Integer>  uploadedChunks(String uploadId, Long userId)
void          uploadChunk(String uploadId, int chunkIndex, int totalChunks, MultipartFile chunk, Long userId)
MediaFile     complete(String uploadId, Long userId)
```

### Implementation

`complete()` takes a Redisson merge lock, checks idempotency via `completedKey`, downloads all chunk objects from MinIO in order while computing MD5, uploads the merged file, records the MediaFile, sets `completedKey`, and best-effort cleans up. ~180 lines covering the full protocol: resumability, idempotency, concurrency, digest, cleanup.

### Depth verdict: **Deep**

Four methods - one per phase of the chunked-upload protocol. The Implementation hides: Redis metadata, Redis Set tracking, MinIO chunk objects, MinIO merged object, Redisson merge lock, MD5 digest, idempotency via `completedKey`, and best-effort cleanup. **Leverage** is high - `MediaController` gets the entire protocol through four calls. **Locality** is high - any change to merge logic, locking, or cleanup lives here.

### Deepening opportunities

1. **`totalChunks` on `uploadChunk` is redundant.** The caller already passed it to `initialize()`. The Implementation re-validates: `if (totalChunks != expectedChunks ...)`. This is defensive paranoia that leaks to the Interface - the caller has to remember a value the service already knows. Drop the param; validate `chunkIndex` against the stored `expectedChunks` only. One fewer param, one fewer thing for callers to get wrong.

2. **`complete()` exception types encode HTTP status.** `BusinessException` (409) for "still merging" or "chunks incomplete"; `IllegalArgumentException` (500) for invalid input; `IllegalStateException` for MD5 unavailable. The comment explains this is deliberate - to avoid 500 for client-retryable states. The Interface doesn't declare this; callers have to read the code. Could be encoded as `CompletionResult` enum (SUCCESS, STILL_MERGING, INCOMPLETE, FAILED) returned from `complete()`, with `MediaFile` accessible on SUCCESS. But Spring's exception-to-status mapping is idiomatic, so the current shape is defensible.

3. **`complete()` returns `MediaFile`.** Couples the upload service to the media entity. Could return `Long mediaId` and let callers load `MediaFile` if they need it. But `MediaFile` is the natural "result" of a completed upload, and `MediaController` immediately returns it to the client, so this is probably fine.

4. **No `abort(uploadId)` method.** If a user abandons an upload, the Redis keys expire after 1 day, but MinIO chunk objects persist until cleanup runs (which only happens on successful `complete()`). Orphaned chunks accumulate. An explicit `abort(uploadId, userId)` that runs cleanup would close this. Minor - operational concern, not a depth concern.

### What to say in an interview

> "Deep module - four methods for a four-phase protocol, ~180 lines hiding Redis state, MinIO objects, a merge lock, MD5, idempotency, and cleanup. The seam sits at the service boundary; callers never see Redis or MinIO. The one thing I'd tighten is `uploadChunk` taking `totalChunks` again - the service already knows it from `initialize()`, so re-passing it is defensive paranoia that leaks into the interface."

---

## Cross-cutting observations

1. **All three modules pass the deletion test.** Remove any one and its complexity scatters to N callers. None is shallow.

2. **The graceful-degradation pattern is consistent.** `VideoEvidenceRetrievalService.index()` swallows Qdrant errors; `vectorScores()` swallows search errors; `embed()` swallows embedding errors. The pattern: telemetry increments a fallback counter, returns empty/empty-list, and the caller continues with degraded behavior. This is correct depth - callers don't branch on infra failure. But it means the Interface "lies" slightly: `index()` may not index, `search()` may return keyword-only results. The trade-off is intentional and worth articulating.

3. **The unused-overload smell in `AnalysisDispatchService` is the only true shallow-module indicator.** Dead interface surface is worse than no interface - it suggests the module is shaped by imagined callers, not real ones. Worth a periodic grep-and-prune.

4. **No module has internal seams exposed for testing.** All three are `@Service` classes with private helpers. Tests would need to go through the public Interface (correct, per "Interface is the test surface"). If tests need to mock Qdrant or DeepSeek, those are constructor-injected Adapters - swappable in tests via the same Interface. This is the right shape.

5. **Two Adapters per seam is the rule, not the goal.** `QdrantVectorStore` has one Adapter (Qdrant). `DeepSeekUtils` has one Adapter (DeepSeek via SiliconFlow). These are "hypothetical seams" - one adapter means the seam _could_ be moved, not that it must. Don't extract interfaces until a second adapter actually appears (e.g., an in-memory Qdrant fake for tests).
