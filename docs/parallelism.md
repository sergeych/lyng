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

This example shows how to launch a coroutine with `launch` which returns [Deferred] instance, the latter have ways to await for the coroutine completion and retrieve possible result.

Launch has the only argument which should be a callable (lambda usually) that is run in parallel (or cooperatively in parallel), and return anything as the result.

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
    }.forEach { it.await() }
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
                val c = counter
                delay(10)
                counter = c + 1
            }
        }
    }.forEach { it.await() }
    assertEquals(4, counter)
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

Closures executed by `launch { ... }` and `flow { ... }` resolve names using the `ClosureScope` rules:

1. **Current frame locals and arguments**: Variables defined within the current closure execution.
2. **Captured lexical ancestry**: Outer local variables captured at the site where the closure was defined (the "lexical environment").
3. **Captured receiver members**: If the closure was defined within a class or explicitly bound to an object, it checks members of that object (`this`), following MRO and respecting visibility.
4. **Caller environment**: Falls back to the calling context (e.g., the caller's `this` or local variables).
5. **Global/Module fallbacks**: Final check for module-level constants and global functions.

Implications:
- Outer locals (e.g., `counter`) stay visible across suspension points.
- Global helpers like `delay(ms)` and `yield()` are available from inside closures.
- If you write your own async helpers, execute user lambdas under `ClosureScope(callScope, capturedCreatorScope)` and avoid manual ancestry walking.

See also: [Scopes and Closures: resolution and safety](scopes_and_closures.md)
