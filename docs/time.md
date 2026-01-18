# Lyng time functions

Lyng date and time support requires importing `lyng.time` packages. Lyng uses simple yet modern time object models:

- `Instant` class for absolute time stamps with platform-dependent resolution.
- `DateTime` class for calendar-aware points in time within a specific time zone.
- `Duration` to represent amount of time not depending on the calendar (e.g., milliseconds, seconds).

## Time instant: `Instant`

Represent some moment of time not depending on the calendar. It is similar to `TIMESTAMP` in SQL or `Instant` in Kotlin.

### Constructing and converting

    import lyng.time

    // default constructor returns time now:
    val t1 = Instant()
    
    // constructing from a number is treated as seconds since unix epoch:
    val t2 = Instant(1704110400) // 2024-01-01T12:00:00Z
    
    // from RFC3339 string:
    val t3 = Instant("2024-01-01T12:00:00.123456Z")
    
    // truncation:
    val t4 = t3.truncateToMinute
    assertEquals(t4.toRFC3339(), "2024-01-01T12:00:00Z")
    
    // to localized DateTime (uses system default TZ if not specified):
    val dt = t3.toDateTime("+02:00")
    assertEquals(dt.hour, 14)

### Instant members

| member                         | description                                             |
|--------------------------------|---------------------------------------------------------|
| epochSeconds: Real             | positive or negative offset in seconds since Unix epoch |
| epochWholeSeconds: Int         | same, but in _whole seconds_. Slightly faster           |
| nanosecondsOfSecond: Int       | offset from epochWholeSeconds in nanos                  |
| isDistantFuture: Bool          | true if it `Instant.distantFuture`                      |
| isDistantPast: Bool            | true if it `Instant.distantPast`                        |
| truncateToMinute: Instant      | create new instance truncated to minute                 |
| truncateToSecond: Instant      | create new instance truncated to second                 |  
| truncateToMillisecond: Instant | truncate new instance to millisecond                    |
| truncateToMicrosecond: Instant | truncate new instance to microsecond                    |
| toRFC3339(): String            | format as RFC3339 string (UTC)                          |
| toDateTime(tz?): DateTime      | localize to a TimeZone (ID string or offset seconds)    |

## Calendar time: `DateTime`

`DateTime` represents a point in time in a specific timezone. It provides access to calendar components like year,
month, and day.

### Constructing

    import lyng.time

    // Current time in system default timezone
    val now = DateTime.now()
    
    // Specific timezone
    val offsetTime = DateTime.now("+02:00")
    
    // From Instant
    val dt = Instant().toDateTime("Z")

    // By components (year, month, day, hour=0, minute=0, second=0, timeZone="UTC")
    val dt2 = DateTime(2024, 1, 1, 12, 0, 0, "Z")

    // From RFC3339 string
    val dt3 = DateTime.parseRFC3339("2024-01-01T12:00:00+02:00")

### DateTime members

| member                           | description                                   |
|----------------------------------|-----------------------------------------------|
| year: Int                        | year component                                |
| month: Int                       | month component (1..12)                       |
| day: Int                         | day of month (alias `dayOfMonth`)             |
| hour: Int                        | hour component (0..23)                        |
| minute: Int                      | minute component (0..59)                      |
| second: Int                      | second component (0..59)                      |
| dayOfWeek: Int                   | day of week (1=Monday, 7=Sunday)              |
| timeZone: String                 | timezone ID string                            |
| toInstant(): Instant             | convert back to absolute Instant              |
| toUTC(): DateTime                | shortcut to convert to UTC                    |
| toTimeZone(tz): DateTime         | convert to another timezone                   |
| addMonths(n): DateTime           | add/subtract months (normalizes end of month) |
| addYears(n): DateTime            | add/subtract years                            |
| toRFC3339(): String              | format with timezone offset                   |
| static now(tz?): DateTime        | create DateTime with current time             |
| static parseRFC3339(s): DateTime | parse RFC3339 string                          |

### Arithmetic and normalization

`DateTime` handles calendar arithmetic correctly:

    val leapDay = Instant("2024-02-29T12:00:00Z").toDateTime("Z")
    val nextYear = leapDay.addYears(1)
    assertEquals(nextYear.day, 28) // Feb 29, 2024 -> Feb 28, 2025

# `Duration` class

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

Each duration instance can be converted to number of any of these time units, as `Real` number, if `d` is a `Duration`
instance:

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


