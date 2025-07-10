# Lyng time functions

Lyng date and time support requires importing `lyng.time` packages. Lyng uses simple yet modern time object models:

- `Instant` class for time stamps with platform-dependent resolution
- `Duration` to represent amount of time not depending on the calendar, e.g. in absolute units (milliseconds, seconds, hours, days)

## Time instant: `Instant`

Represent some moment of time not depending on the calendar (calendar for example may b e changed, daylight saving time can be for example introduced or dropped). It is similar to `TIMESTAMP` in SQL or `Instant` in Kotlin. Some moment of time; not the calendar date.

Instant is comparable to other Instant. Subtracting instants produce `Duration`, period in time that is not dependent on the calendar, e.g. absolute time period.

It is possible to add or subtract `Duration` to and from `Instant`, that gives another `Instant`.

Instants are converted to and from `Real` number of seconds before or after Unix Epoch, 01.01.1970. Constructor with single number parameter constructs from such number of seconds,
and any instance provide `.epochSeconds` member:

    import lyng.time

    // default constructor returns time now:
    val t1 = Instant()
    val t2 = Instant()
    assert( t2 - t1 < 1.millisecond )
    assert( t2.epochSeconds - t1.epochSeconds < 0.001 )
    >>> void

## Constructing

    import lyng.time

    // empty constructor gives current time instant using system clock:
    val now = Instant()

    // constructor with Instant instance makes a copy:
    assertEquals( now, Instant(now) )

    // constructing from a number is trated as seconds since unix epoch:
    val copyOfNow = Instant( now.epochSeconds )

    // note that instant resolution is higher that Real can hold
    // so reconstructed from real slightly differs:
    assert( abs( (copyOfNow - now).milliseconds ) < 0.01 )
    >>> void

The resolution of system clock could be more precise and double precision real number of `Real`, keep it in mind.

## Comparing and calculating periods

    import lyng.time
    
    val now = Instant()
    
    // you cam add or subtract periods, and compare
    assert( now - 5.minutes < now )
    val oneHourAgo = now - 1.hour
    assertEquals( now, oneHourAgo + 1.hour)

    >>> void

## Getting the max precision

Normally, subtracting instants gives precision to microseconds, which is well inside the jitter
the language VM adds. Still `Instant()` captures most precise system timer at hand and provide
inner value of 12 bytes, up to nanoseconds (hopefully). To access it use:

    import lyng.time

    // capture time
    val now = Instant()

    // this is Int value, number of whole epoch 
    // milliseconds to the moment, it fits 8 bytes Int well
    val seconds = now.epochWholeSeconds
    assert(seconds is Int)

    // and this is Int value of nanoseconds _since_ the epochMillis,
    // it effectively add 4 more mytes int:
    val nanos = now.nanosecondsOfSecond
    assert(nanos is Int)
    assert( nanos in 0..999_999_999 )

    // we can construct epochSeconds from these parts:
    assertEquals( now.epochSeconds, nanos * 1e-9 + seconds )
    >>> void

## Formatting instants

You can freely use `Instant` in string formatting. It supports usual sprintf-style formats:

        import lyng.time
        val now = Instant()

        // will be something like "now: 12:10:05"
        val currentTimeOnly24 =  "now: %tT"(now)

        // we can extract epoch second with formatting too,
        // this was since early C time

        // get epoch while seconds from formatting
        val unixEpoch = "Now is %ts since unix epoch"(now)
        
        // and it is the same as now.epochSeconds, int part:
        assertEquals( unixEpoch, "Now is %d since unix epoch"(now.epochSeconds.toInt()) )
        >>> void

See the [complete list of available formats](https://github.com/sergeych/mp_stools?tab=readme-ov-file#datetime-formatting) and the [formatting reference](https://github.com/sergeych/mp_stools?tab=readme-ov-file#printf--sprintf): it all works in Lyng as `"format"(args...)`!

## Instant members

| member                   | description                                             |
|--------------------------|---------------------------------------------------------|
| epochSeconds: Real       | positive or negative offset in seconds since Unix epoch |
| epochWholeSeconds: Int   | same, but in _whole seconds_. Slightly faster           |
| nanosecondsOfSecond: Int | offset from epochWholeSeconds in nanos (1)              |
| isDistantFuture: Bool    | true if it `Instant.distantFuture`                      |
| isDistantPast: Bool      | true if it `Instant.distantPast`                        |

(1)
: The value of nanoseconds is to be added to `epochWholeSeconds` to get exact time point. It is in 0..999_999_999 range. The precise time instant value therefore needs as for now 12 bytes integer; we might use bigint later (it is planned to be added)

## Class members

| member                         | description                                             |
|--------------------------------|---------------------------------------------------------|
| Instant.distantPast: Instant   | most distant instant in past                            |
| Instant.distantFuture: Instant | most distant instant in future                          |

# `Duraion` class

Represent absolute time distance between two `Instant`.

    import lyng.time
    val t1 = Instant()

    // yes we can delay to period, and it is not blocking. is suspends!
    delay(1.millisecond)

    val t2 = Instant()
    // be suspend, so actual time may vary:
    assert( t2 - t1 >= 1.millisecond)
    assert( t2 - t1 < 100.millisecond)
    >>> void

Duration can be converted from numbers, like `5.minutes` and so on. Extensions are created for
`Int` and `Real`, so for n as Real or Int it is possible to create durations::

- `n.millisecond`, `n.milliseconds`
- `n.second`, `n.seconds`
- `n.minute`, `n.minutes`
- `n.hour`, `n.hours`
- `n.day`, `n.days`

The bigger time units like months or years are calendar-dependent and can't be used with `Duration`.

Each duration instance can be converted to number of any of these time units, as `Real` number, if `d` is a `Duration` instance:

- `d.microseconds`
- `d.milliseconds`
- `d.seconds`
- `d.minutes`
- `d.hours`
- `d.days`

for example

    import lyng.time
    assertEquals( 60, 1.minute.seconds )
    assertEquals( 10.milliseconds, 0.01.seconds )

    >>> void

# Utility functions

## delay(duration: Duration)

Suspends current coroutine for at least the specified duration.


