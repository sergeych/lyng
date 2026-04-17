# Channel

A `Channel` is a **hot, bidirectional pipe** for passing values between concurrently running coroutines.
Unlike a [Flow], which is cold and replayed on every collection, a `Channel` is stateful: each value
sent is consumed by exactly one receiver.

Channels model the classic _producer / consumer_ pattern and are the right tool when:

- two or more coroutines need to exchange individual values at their own pace;
- you want back-pressure (rendezvous) or explicit buffering control;
- you need a push-based, hot data source (opposite of the pull-based, cold [Flow]).

## Constructors

```
Channel()            // rendezvous — sender and receiver must meet
Channel(n: Int)      // buffered — sender may run n items ahead of the receiver
Channel(Channel.UNLIMITED)  // no limit on buffered items
```

**Rendezvous** (`Channel()`, capacity 0): `send` suspends until a matching `receive` is ready,
and vice-versa. This gives the tightest synchronisation and the smallest memory footprint.

**Buffered** (`Channel(n)`): `send` only suspends when the internal buffer is full. Allows the
producer to get up to _n_ items ahead of the consumer.

**Unlimited** (`Channel(Channel.UNLIMITED)`): `send` never suspends. Useful when the producer is
bursty and you do not want it blocked, but be careful not to grow the buffer without bound.

## Sending and receiving

```lyng
val ch = Channel()         // rendezvous channel

val producer = launch {
    ch.send("hello")       // suspends until the receiver is ready
    ch.send("world")
    ch.close()             // signal: no more values
}

val a = ch.receive()       // suspends until "hello" arrives
val b = ch.receive()       // suspends until "world" arrives
val c = ch.receive()       // channel is closed and drained → null
assertEquals("hello", a)
assertEquals("world", b)
assertEquals(null,    c)
```

`receive()` returns `null` when the channel is both **closed** _and_ **fully drained** — that is
the idiomatic loop termination condition:

```lyng
val ch = Channel(4)

launch {
    for (i in 1..5) ch.send(i)
    ch.close()
}

var item = ch.receive()
while (item != null) {
    println(item)
    item = ch.receive()
}
```

## Non-suspending poll

`tryReceive()` never suspends. It returns the next buffered value, or `null` if the buffer is
empty or the channel is closed.

```lyng
val ch = Channel(8)
ch.send(42)
println(ch.tryReceive())   // 42
println(ch.tryReceive())   // null — nothing buffered right now
```

Use `tryReceive` for _polling_ patterns where blocking would be unacceptable, for example when
combining channel checks with other work inside a coroutine loop.

## Closing a channel

`close()` marks the channel so that no further `send` calls are accepted. Any items already in the
buffer can still be received. Once the buffer is drained, `receive()` returns `null` and
`isClosedForReceive` becomes `true`.

```lyng
val ch = Channel(2)
ch.send(1)
ch.send(2)
ch.close()

assert(ch.isClosedForSend)
assert(!ch.isClosedForReceive)   // still has 2 buffered items

ch.receive()  // 1
ch.receive()  // 2
assert(ch.isClosedForReceive)    // drained
```

Calling `send` after `close()` throws `IllegalStateException`.

## Properties

| property            | type   | description                                              |
|---------------------|--------|----------------------------------------------------------|
| `isClosedForSend`   | `Bool` | `true` after `close()` is called                         |
| `isClosedForReceive`| `Bool` | `true` when closed _and_ every buffered item is consumed |

## Methods

| method          | suspends | description                                                                      |
|-----------------|----------|----------------------------------------------------------------------------------|
| `send(value)`   | yes      | send a value; suspends when buffer full (rendezvous: always until partner ready) |
| `receive()`     | yes      | receive next value; suspends when empty; returns `null` when closed + drained    |
| `tryReceive()`  | no       | return next buffered value or `null`; never suspends                             |
| `close()`       | no       | signal end of production; existing buffer items are still receivable             |

## Static constants

| constant            | value            | description                         |
|---------------------|------------------|-------------------------------------|
| `Channel.UNLIMITED` | `Int.MAX_VALUE`  | capacity for an unlimited-buffer channel |

## Common patterns

### Producer / consumer

```lyng
val ch = Channel()
val results = []
val mu = Mutex()

val consumer = launch {
    var item = ch.receive()
    while (item != null) {
        mu.withLock { results += item }
        item = ch.receive()
    }
}

launch {
    for (i in 1..5) ch.send("msg:$i")
    ch.close()
}.await()

consumer.await()
println(results)
```

### Fan-out: one channel, many consumers

```lyng
val ch = Channel(16)

// multiple consumers
val workers = (1..4).map { id ->
    launch {
        var task = ch.receive()
        while (task != null) {
            println("worker $id handles $task")
            task = ch.receive()
        }
    }
}

// single producer
for (i in 1..20) ch.send(i)
ch.close()

workers.forEach { it.await() }
```

### Ping-pong between two coroutines

```lyng
val ping = Channel()
val pong = Channel()

launch {
    repeat(3) {
        val msg = ping.receive()
        println("got: $msg → sending pong")
        pong.send("pong")
    }
}

repeat(3) {
    ping.send("ping")
    println(pong.receive())
}
```

## Channel vs Flow

| | [Flow] | Channel |
|---|---|---|
| **temperature** | cold (lazy) | hot (eager) |
| **replay** | every collector gets a fresh run | each item is consumed once |
| **consumers** | any number; each gets all items | one receiver per item |
| **back-pressure** | built-in via rendezvous | configurable (rendezvous / buffered / unlimited) |
| **typical use** | transform pipelines, sequences | producer–consumer, fan-out |

## See also

- [parallelism] — `launch`, `Deferred`, `Mutex`, `Flow`, and the full concurrency picture
- [Flow] — cold async sequences

[Flow]: parallelism.md#flow
[parallelism]: parallelism.md
