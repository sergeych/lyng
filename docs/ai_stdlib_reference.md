# Lyng Stdlib Reference for AI Agents (Compact)

[//]: # (excludeFromIndex)

Purpose: fast overview of what is available by default and what must be imported.

Sources: `lynglib/src/commonMain/kotlin/net/sergeych/lyng/Script.kt`, `lynglib/stdlib/lyng/root.lyng`, `lynglib/src/commonMain/kotlin/net/sergeych/lyng/stdlib_included/observable_lyng.kt`.

## 1. Default Availability
- Normal scripts are auto-seeded with `lyng.stdlib` (default import manager path).
- Root runtime scope also exposes global constants/functions directly.

## 2. Core Global Functions (Root Scope)
- IO/debug: `print`, `println`, `traceScope`.
- Invocation/util: `call`, `run`, `dynamic`, `cached`, `lazy`.
- Assertions/tests: `assert`, `assertEquals`/`assertEqual`, `assertNotEquals`, `assertThrows`.
- Preconditions: `require`, `check`.
- Async/concurrency: `launch`, `yield`, `flow`, `delay`.
  - `Deferred.cancel()` cancels an active task.
  - `Deferred.await()` throws `CancellationException` if that task was cancelled.
  - `Iterable<Deferred>.joinAll()` awaits every deferred in iteration order and returns a `List` of results.
- Math: `floor`, `ceil`, `round`, `sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `sinh`, `cosh`, `tanh`, `asinh`, `acosh`, `atanh`, `exp`, `ln`, `log10`, `log2`, `pow`, `sqrt`, `abs`, `clamp`.
  - These helpers also accept `lyng.decimal.Decimal`.
  - Exact Decimal path today: `abs`, `floor`, `ceil`, `round`, and `pow` with integral exponent.
  - Temporary Decimal path for the rest: convert `Decimal -> Real`, compute, then convert back to `Decimal`.
  - Treat that bridge as temporary; prefer native Decimal implementations when they become available.

## 3. Core Global Constants/Types
- Values: `Unset`, `π`.
- Primitive/class symbols: `Object`, `Int`, `Real`, `Bool`, `Char`, `String`, `Class`, `Callable`.
- Collections/types: `Iterable`, `Iterator`, `Collection`, `Array`, `List`, `ImmutableList`, `Set`, `ImmutableSet`, `Map`, `ImmutableMap`, `MapEntry`, `Range`, `RingBuffer`.
- Random: singleton `Random` and class `SeededRandom`.
- Async types: `Deferred`, `CompletableDeferred`, `Mutex`, `Flow`, `FlowBuilder`.
- Async exception: `CancellationException`.
- Delegation types: `Delegate`, `DelegateContext`.
- Regex types: `Regex`, `RegexMatch`.
- Also present: `Math.PI` namespace constant.

## 4. `lyng.stdlib` Module Surface (from `root.lyng`)
### 4.1 Extern class declarations
- Exceptions/delegation base: `Exception`, `CancellationException`, `IllegalArgumentException`, `NotImplementedException`, `Delegate`.
- Collections and iterables: `Iterable<T>`, `Iterator<T>`, `Collection<T>`, `Array<T>`, `List<T>`, `ImmutableList<T>`, `Set<T>`, `ImmutableSet<T>`, `Map<K,V>`, `ImmutableMap<K,V>`, `MapEntry<K,V>`, `RingBuffer<T>`.
- Host iterator bridge: `KotlinIterator<T>`.
- Random APIs: `extern object Random`, `extern class SeededRandom`.

### 4.2 High-use extension APIs
- Iteration/filtering: `forEach`, `filter`, `filterFlow`, `filterNotNull`, `filterFlowNotNull`, `drop`, `dropLast`, `takeLast`.
- Search/predicates: `findFirst`, `findFirstOrNull`, `any`, `all`, `count`, `first`, `last`.
- Mapping/aggregation: `map`, `flatMap`, `flatten`, `sum`, `sumOf`, `minOf`, `maxOf`.
- Ordering and list building: `sorted`, `sortedBy`, `shuffled`, `List.sort`, `List.sortBy`, `List.fill`.
  - `List.fill(size) { index -> ... }` constructs a new `List<T>` by evaluating the block once per index from `0` to `size - 1`.
- String helper: `joinToString`, `String.re`.

### 4.3 Delegation helpers
- `enum DelegateAccess { Val, Var, Callable }`
- `interface Delegate<T,ThisRefType=void>` with `getValue`, `setValue`, `invoke`, `bind`.
- `class lazy<T,...>` delegate implementation.
- `fun with(self, block)` helper.

### 4.4 Other module-level symbols
- `$~` (last regex match object).
- `TODO(message?)` utility.
- `StackTraceEntry` class.
- `Random.nextInt()`, `Random.nextFloat()`, `Random.next(range)`, `Random.seeded(seed)`.
- `SeededRandom.nextInt()`, `SeededRandom.nextFloat()`, `SeededRandom.next(range)`.

## 5. Additional Built-in Modules (import explicitly)
- `import lyng.observable`
  - `Observable`, `Subscription`, `ObservableList`, `ListChange` and change subtypes, `ChangeRejectionException`.
- `import lyng.decimal`
  - `Decimal`, `DecimalContext`, `DecimalRounding`, `withDecimalContext(...)`.
  - Kotlin host helper: `ScopeFacade.newDecimal(BigDecimal)` wraps an ionspin host decimal as a Lyng `Decimal`.
- `import lyng.complex`
  - `Complex`, `complex(re, im)`, `cis(angle)`, and numeric embedding extensions such as `2.i` / `3.re`.
- `import lyng.matrix`
  - `Matrix`, `Vector`, `matrix(rows)`, `vector(values)`, dense linear algebra, inversion, solving, and matrix slicing with `m[row, col]`.
- `import lyng.buffer`
  - `Buffer`, `MutableBuffer`.
- `import lyng.legacy_digest`
  - `LegacyDigest.sha1(data): String` — SHA-1 hex digest; `data` may be `String` (UTF-8) or `Buffer` (raw bytes).
  - ⚠️ Cryptographically broken. Use only for legacy protocol / file-format compatibility.
- `import lyng.serialization`
  - `Lynon` serialization utilities.
- `import lyng.time`
  - `Instant`, `Date`, `DateTime`, `Duration`, and module `delay`.

## 6. Optional (lyngio) Modules
Requires installing `lyngio` into the import manager from host code.
- `import lyng.io.fs` (filesystem `Path` API)
- `import lyng.io.process` (process execution API)
- `import lyng.io.console` (console capabilities, geometry, ANSI/output, events)
- `import lyng.io.http` (HTTP/HTTPS client API)
- `import lyng.io.http.server` (minimal HTTP/1.1 and WebSocket server API)
- `import lyng.io.ws` (WebSocket client API; currently supported on JVM, capability-gated elsewhere)
- `import lyng.io.net` (TCP/UDP transport API; currently supported on JVM, capability-gated elsewhere)
- Shared network value-type packages are also available when installed by host code:
  - `import lyng.io.http.types` (`HttpHeaders`)
  - `import lyng.io.ws.types` (`WsMessage`)
  - `import lyng.io.net.types` (`IpVersion`, `SocketAddress`, `Datagram`)

## 7. AI Generation Tips
- Assume `lyng.stdlib` APIs exist in regular script contexts.
- For platform-sensitive code (`fs`, `process`, `console`, `http`, `ws`, `net`), gate assumptions and mention required module install.
- Prefer extension-method style (`items.filter { ... }`) and standard scope helpers (`let`/`also`/`apply`/`run`).
