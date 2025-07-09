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

## Instant members

| member                | description                                             |
|-----------------------|---------------------------------------------------------|
| epochSeconds: Real    | positive or negative offset in seconds since Unix epoch |
| isDistantFuture: Bool | true if it `Instant.distantFuture`                      |
| isDistantPast: Bool   | true if it `Instant.distantPast`                        |

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

- `d.milliseconds`
- `d.seconds`
- `d.minutes`
- `d.hours`
- `d.days`

for example

    import lyng.time
    assertEquals( 60, 1.minute.seconds )
    >>> void

# Utility functions

## delay(duration: Duration)

Suspends current coroutine for at least the specified duration.


