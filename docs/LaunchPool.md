# LaunchPool

`LaunchPool` is a bounded-concurrency task pool: you submit lambdas with `launch`, and the pool runs them using a fixed number of worker coroutines.

## Constructor

```
LaunchPool(maxWorkers, maxQueueSize = Channel.UNLIMITED)
```

| Parameter | Description |
|-----------|-------------|
| `maxWorkers` | Maximum number of tasks that run in parallel. |
| `maxQueueSize` | Maximum number of tasks that may wait in the queue. When the queue is full, `launch` suspends the caller until space becomes available. Defaults to `Channel.UNLIMITED` (no bound). |

## Methods

### `launch(lambda): Deferred`

Schedules `lambda` for execution and returns a `Deferred` for its result.

- Suspends if the queue is full (`maxQueueSize` reached).
- Throws `IllegalStateException` if the pool is already closed or cancelled.
- Any exception thrown by `lambda` is captured in the returned `Deferred` and **does not escape the pool**.

```lyng
val pool = LaunchPool(4)
val d1 = pool.launch { computeSomething() }
val d2 = pool.launch { computeOther() }
pool.closeAndJoin()
println(d1.await())
println(d2.await())
```

### `closeAndJoin()`

Stops accepting new tasks and suspends until all queued and running tasks complete normally. After this call, any further `launch` throws `IllegalStateException`. Idempotent — safe to call multiple times.

### `cancel()`

Immediately closes the queue and cancels all worker coroutines. Queued but unstarted tasks are discarded. After this call, `launch` throws `IllegalStateException`. Idempotent.

### `cancelAndJoin()`

Like `cancel()`, but also suspends until all worker coroutines have stopped. Useful when you need to be sure no worker code is still running before proceeding. Idempotent.

## Exception handling

Exceptions from submitted lambdas are captured per-task in the returned `Deferred`. The pool itself continues running after a task failure:

```lyng
val pool = LaunchPool(2)
val good = pool.launch { 42 }
val bad  = pool.launch { throw IllegalArgumentException("boom") }
pool.closeAndJoin()

assertEquals(42, good.await())
assertThrows(IllegalArgumentException) { bad.await() }
```

## Bounded queue / back-pressure

When `maxQueueSize` is set, the producer suspends if the queue fills up, providing automatic back-pressure:

```lyng
// 1 worker, queue of 2 — producer can be at most 2 tasks ahead of what's running
val pool = LaunchPool(1, 2)
val d1 = pool.launch { delay(10); "a" }
val d2 = pool.launch { delay(10); "b" }
val d3 = pool.launch { delay(10); "c" }   // suspends until d1 is picked up by the worker
pool.closeAndJoin()
```

## Collecting all results

`launch` returns a `Deferred`, so you can collect results with `joinAll()`:

```lyng
val pool = LaunchPool(4)
val jobs = (1..10).map { n -> pool.launch { n * n } }
pool.closeAndJoin()
val results = jobs.joinAll()
// results == [1, 4, 9, 16, 25, 36, 49, 64, 81, 100]
```

## Concurrency limit in practice

With `maxWorkers = 2`, at most 2 tasks run simultaneously regardless of how many are queued:

```lyng
val mu = Mutex()
var active = 0
var maxSeen = 0

val pool = LaunchPool(2)
(1..8).map {
    pool.launch {
        mu.withLock { active++; if (active > maxSeen) maxSeen = active }
        delay(5)
        mu.withLock { active-- }
    }
}
pool.closeAndJoin()
assert(maxSeen <= 2)
```

## See also

- [parallelism.md](parallelism.md) — `launch`, `Deferred`, `Mutex`, `Channel`, and coroutine basics
- [Channel.md](Channel.md) — the underlying channel primitive used by `LaunchPool`
