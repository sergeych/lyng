# Multithreading/parallel execution

[//]: # (topMenu)

Lyng is built to me multithreaded where possible (e.g. all targets byt JS and wasmJS as for now)
and cooperatively parallel (coroutine based) everywhere.

In Lyng, every function, every lambda are _coroutines_. It means, you can have as many of these as you want without risking running out of memory on threads stack, or get too many threads.

Depending on the platform, these coroutines may be executed on different CPU and cores, too, truly in parallel. Where not, like Javascript browser, they are still executed cooperatively. You should not care about the platform capabilities, just call `launch`:

    // track coroutine call: 
    var xIsCalled = false

    // launch coroutine in parallel
    val x = launch { 
        // wait 10ms to let main code to be executed
        delay(10)
        // now set the flag
        xIsCalled = true
        // and return something useful:
        "ok"
    }
    // corouine is launhed, but not yet executed
    // due to delay call:
    assert(!xIsCalled)
    
    // now we wait for it to be executed:
    assertEquals( x.await(), "ok")

    // now glag should be set:
    assert(xIsCalled)
    >>> void

This example shows how to launch a coroutine with `launch` which returns [Deferred] instance, the latter have ways to await for the coroutine completion, cancel it if it is no longer needed, and retrieve possible result.

Launch has the only argument which should be a callable (lambda usually) that is run in parallel (or cooperatively in parallel), and return anything as the result.

If you no longer need the result, cancel the deferred. Awaiting a cancelled deferred throws `CancellationException`:

    var reached = false
    val work = launch {
        delay(100)
        reached = true
        "ok"
    }
    work.cancel()
    assertThrows(CancellationException) { work.await() }
    assert(work.isCancelled)
    assert(!work.isActive)
    assert(!reached)
    >>> void

## Synchronization: Mutex

Suppose we have a resource, that could be used concurrently, a counter in our case. If we won't protect it, concurrent usage cause RC, Race Condition, providing wrong result:

    var counter = 0
    
    (1..50).map { 
        launch {
            // slow increment:
            val c = counter
            delay(100)
            counter = c + 1
        }
    }.forEach { (it as Deferred).await() }
    assert(counter < 50) { "counter is "+counter }
    >>> void

The obviously wrong result is not 4, as all coroutines capture the counter value, which is 1, then sleep for 5ms, then save 1 + 1 as result. May some coroutines will pass, so it will be 1 or 2, most likely.

Using [Mutex] makes it all working:

    var counter = 0
    val mutex = Mutex()
    
    (1..4).map { 
        launch {
            // slow increment:
            mutex.withLock {
                val c = counter ?: 0
                counter = c + 1
            }
        }
    }.forEach { (it as Deferred).await() }
    assert(counter in 1..4)
    >>> void

now everything works as expected: `mutex.withLock` makes them all be executed in sequence, not in parallel.


## Completable deferred

Sometimes it is convenient to manually set completion status of some deferred result. This is when [CompletableDeferred] is used:

    // this variable will be completed later:
    val done = CompletableDeferred()
    
    // complete it ater delay
    launch { 
        delay(10)
        // complete it setting the result:
        done.complete("ok")
    }
    
    // now it is still not completed: coroutine is delayed
    // (ot not started on sinthe-threaded platforms):
    assert(!done.isCompleted)
    assert(done.isActive)

    // then we can just await it as any other deferred:
    assertEquals( done.await(), "ok")
    // and as any other deferred it is now complete:
    assert(done.isCompleted)

## True parallelism

Cooperative, coroutine-based parallelism is automatically available on all platforms. Depending on the platform, though, the coroutines could be dispatched also in different threads; where there are multiple cores and/or CPU available, it means the coroutines could be exuted truly in parallel, unless [Mutex] is used:

| platofrm   | multithreaded |
|------------|---------------|
| JVM        | yes           |
| Android    | yes           |
| Javascript | NO            |
| wasmJS     | NO            |
| IOS        | yes           |
| MacOSX     | yes           |
| Linux      | yes           |
| Windows    | yes           |

So it is important to always use [Mutex] where concurrent execution could be a problem (so called Race Conditions, or RC).

## Yield

When the coroutine is executed, on the single-threaded environment all other coroutines are suspended until active one will wait for something. Sometimes, it is undesirable; the coroutine may perform long calculations or some other CPU consuming task. The solution is to call `yield()` periodically. Unlike `delay()`, yield does not pauses the coroutine for some specified time, but it just makes all other coroutines to be executed. In other word, yield interrupts current coroutines and out it to the end of the dispatcher list of active coroutines. It is especially important on Javascript and wasmJS targets as otherwise UI thread could be blocked. 

Usage example:

    fun someLongTask() { // ...
        do {
            // execute step
            if( done ) break
            yield()
        } while(true)
    }

# Data exchange for coroutines

## Flow

Flow is an async cold sequence; it is named after kotlin's Flow as it resembles it closely. The cold means the flow is only evaluated when iterated (collected, in Kotlin terms), before it is inactive. Sequence means that it is potentially unlimited, as in our example of glorious Fibonacci number generator:

    // Fibonacch numbers flow! 
    val f = flow {
        println("Starting generator")
        var n1 = 0
        var n2 = 1
        emit(n1)
        emit(n2)
        while(true) {
            val n = n1 + n2
            emit(n)
            n1 = n2
            n2 = n
        }
    }
    val correctFibs = [0, 1, 1, 2, 3, 5, 8, 13, 21, 34, 55, 89, 144, 233, 377, 610, 987, 1597, 2584, 4181, 6765]
    println("Generation starts")
    assertEquals( correctFibs, f.take(correctFibs.size))
    >>> Generation starts
    >>> Starting generator
    >>> void

Great: the generator is not executed until collected bu the `f.take()` call, which picks specified number of elements from the flow, can cancel it.

Important difference from the channels or like, every time you collect the flow, you collect it anew:

    var isStarted = false
    val f = flow {
            emit("start")
            isStarted = true
            (1..4).forEach { emit(it) }
    }
    // flow is not yet started, e.g. not got execited, 
    // that is called 'cold':
    assertEquals( false, isStarted )

    // let's collect flow:
    val result = []
    for( x in f ) result += x
    println(result)

    assertEquals( true, isStarted)

    // let's collect it once again, it should be the same:
    println(f.toList())

    // and again:
    assertEquals( result, f.toList() )

    >>> [start,1,2,3,4]
    >>> [start,1,2,3,4]
    >>> void

Notice that flow's lambda is not called until actual collection is started. Cold flows are
better in terms of resource consumption.

Flows allow easy transforming of any [Iterable]. See how the standard Lyng library functions use it:

    fun Iterable.filter(predicate) {
        val list = this
        flow {
            for( item in list ) {
                if( predicate(item) ) {
                    emit(item)
                }
            }
        }
    }

## Channel

A [Channel] is a **hot pipe** between coroutines: values are pushed in by a producer and pulled out by a consumer, with each value consumed exactly once.

Unlike a `Flow` (which is cold and re-runs its generator on every collection), a `Channel` is stateful — the right tool for classic _producer / consumer_ work.

    val ch = Channel()        // rendezvous: sender waits for receiver

    val producer = launch {
        for (i in 1..5) ch.send(i)
        ch.close()            // signal: no more values
    }

    var item = ch.receive()   // suspends until a value is ready
    while (item != null) {
        println(item)
        item = ch.receive()
    }
    // prints 1 2 3 4 5

`receive()` returns `null` when the channel is both closed and fully drained — that is the idiomatic loop termination condition.

Channels can also be buffered so the producer can run ahead:

    val ch = Channel(4)       // buffer up to 4 items without blocking

    ch.send(10)
    ch.send(20)
    ch.send(30)
    ch.close()

    assertEquals(10, ch.receive())
    assertEquals(20, ch.receive())
    assertEquals(30, ch.receive())
    assertEquals(null, ch.receive())   // drained

For the full API — including `tryReceive`, `Channel.UNLIMITED`, and the fan-out / ping-pong patterns — see the [Channel] reference page.

| | Flow | Channel |
|---|---|---|
| **temperature** | cold (lazy) | hot (eager) |
| **replay** | every collector gets a fresh run | each item consumed once |
| **consumers** | any number, each gets all items | one receiver per item |
| **typical use** | transform pipelines, sequences | producer–consumer, fan-out |

[Channel]: Channel.md

[Iterable]: Iterable.md

## Scope frame pooling (JVM)

Lyng includes an optional optimization for function/method calls on JVM: scope frame pooling, toggled by the runtime flag `PerfFlags.SCOPE_POOL`.

- Default: `SCOPE_POOL` is OFF on JVM.
- Rationale: the current `ScopePool` implementation is not thread‑safe. Lyng targets multi‑threaded execution on most platforms, therefore we keep pooling disabled by default until a thread‑safe design is introduced.
- When safe to enable: single‑threaded runs (e.g., micro‑benchmarks or scripts executed on a single worker) where no scopes are shared across threads.
- How to toggle at runtime (Kotlin/JVM tests):
  - `PerfFlags.SCOPE_POOL = true` to enable.
  - `PerfFlags.SCOPE_POOL = false` to disable.
- Expected effect (from our JVM micro‑benchmarks): in deep call loops, enabling pooling reduced total time by about 1.38× in a dedicated pooling benchmark; mileage may vary depending on workload.

Future work: introduce thread‑safe pooling (e.g., per‑thread pools or confinement strategies) before considering enabling it by default in multi‑threaded environments.

### Closures inside coroutine helpers (launch/flow)

Closures executed by `launch { ... }` and `flow { ... }` use **compile‑time resolution** just like any other Lyng code:

- **Captured locals are slots**: outer locals are resolved at compile time and captured as frame‑slot references, so they remain visible across suspension points.
- **Members are statically resolved**: member access requires a statically known receiver type or an explicit cast (except `Object` members).
- **No runtime fallbacks**: there is no dynamic name lookup or “search parent scopes” at runtime for missing symbols.

Implications:
- Global helpers like `delay(ms)` and `yield()` must be imported/known at compile time.
- If you need dynamic access, use explicit helpers (e.g., `dynamic { ... }`) rather than relying on scope resolution.

See also: [Scopes and Closures: compile-time resolution](scopes_and_closures.md)
