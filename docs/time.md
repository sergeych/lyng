# Lyng time functions

Lyng date and time support requires importing `lyng.time`. The module provides four related types:

- `Instant` for absolute timestamps.
- `Date` for calendar dates without time-of-day or timezone.
- `DateTime` for calendar-aware points in time in a specific timezone.
- `Duration` for absolute elapsed time.

## Time instant: `Instant`

`Instant` represents some moment of time independently of the calendar. It is similar to SQL `TIMESTAMP`
or Kotlin `Instant`.

### Constructing and converting

    import lyng.time

    val t1 = Instant()
    val t2 = Instant(1704110400)
    val t3 = Instant("2024-01-01T12:00:00.123456Z")

    val t4 = t3.truncateToMinute()
    assertEquals("2024-01-01T12:00:00Z", t4.toRFC3339())

    val dt = t3.toDateTime("+02:00")
    assertEquals(14, dt.hour)

    val d = t3.toDate("Z")
    assertEquals(Date(2024, 1, 1), d)

### Instant members

| member                         | description                                          |
|--------------------------------|------------------------------------------------------|
| epochSeconds: Real             | offset in seconds since Unix epoch                   |
| epochWholeSeconds: Int         | whole seconds since Unix epoch                       |
| nanosecondsOfSecond: Int       | nanoseconds within the current second                |
| isDistantFuture: Bool          | true if it is `Instant.distantFuture`                |
| isDistantPast: Bool            | true if it is `Instant.distantPast`                  |
| truncateToMinute(): Instant    | truncate to minute precision                         |
| truncateToSecond(): Instant    | truncate to second precision                         |
| truncateToMillisecond(): Instant | truncate to millisecond precision                  |
| truncateToMicrosecond(): Instant | truncate to microsecond precision                  |
| toRFC3339(): String            | format as RFC3339 string in UTC                      |
| toDateTime(tz?): DateTime      | localize to a timezone                               |
| toDate(tz?): Date              | convert to a calendar date in a timezone             |

## Calendar date: `Date`

`Date` represents a pure calendar date. It has no time-of-day and no attached timezone. Use it for values
like birthdays, due dates, invoice dates, and SQL `DATE` columns.

### Constructing

    import lyng.time

    val today = Date()
    val d1 = Date(2026, 4, 15)
    val d2 = Date("2024-02-29")
    val d3 = Date.parseIso("2024-02-29")
    val d4 = Date(DateTime(2024, 5, 20, 15, 30, 45, "+02:00"))
    val d5 = Date(Instant("2024-01-01T23:30:00Z"), "+02:00")

### Date members

| member                         | description                                                |
|--------------------------------|------------------------------------------------------------|
| year: Int                      | year component                                             |
| month: Int                     | month component (1..12)                                    |
| day: Int                       | day of month (alias `dayOfMonth`)                          |
| dayOfWeek: Int                 | day of week (1=Monday, 7=Sunday)                           |
| dayOfYear: Int                 | day of year (1..365/366)                                   |
| isLeapYear: Bool               | whether this date is in a leap year                        |
| lengthOfMonth: Int             | number of days in this month                               |
| lengthOfYear: Int              | 365 or 366                                                 |
| toIsoString(): String          | ISO `YYYY-MM-DD` string                                    |
| toSortableString(): String     | alias to `toIsoString()`                                   |
| toDateTime(tz="Z"): DateTime  | start-of-day `DateTime` in the specified timezone          |
| atStartOfDay(tz="Z"): DateTime | alias to `toDateTime()`                                  |
| addDays(n): Date               | add or subtract calendar days                              |
| addMonths(n): Date             | add or subtract months, normalizing end-of-month           |
| addYears(n): Date              | add or subtract years                                      |
| daysUntil(other): Int          | calendar days until `other`                                |
| daysSince(other): Int          | calendar days since `other`                                |
| static today(tz?): Date        | today in the specified timezone                            |
| static parseIso(s): Date       | parse ISO `YYYY-MM-DD`                                     |

### Date arithmetic

`Date` supports only whole-day arithmetic. This is deliberate: calendar dates should not silently accept
sub-day durations.

    import lyng.time

    val d1 = Date(2026, 4, 15)
    val d2 = d1.addDays(10)

    assertEquals(Date(2026, 4, 25), d2)
    assertEquals(Date(2026, 4, 18), d1 + 3.days)
    assertEquals(Date(2026, 4, 12), d1 - 3.days)
    assertEquals(10, d1.daysUntil(d2))
    assertEquals(10, d2.daysSince(d1))
    assertEquals(10, d2 - d1)

### Date conversions

    import lyng.time

    val i = Instant("2024-01-01T23:30:00Z")
    assertEquals(Date(2024, 1, 1), i.toDate("Z"))
    assertEquals(Date(2024, 1, 2), i.toDate("+02:00"))

    val dt = DateTime(2024, 5, 20, 15, 30, 45, "+02:00")
    assertEquals(Date(2024, 5, 20), dt.date)
    assertEquals(Date(2024, 5, 20), dt.toDate())
    assertEquals(DateTime(2024, 5, 20, 0, 0, 0, "Z"), Date(2024, 5, 20).toDateTime("Z"))
    assertEquals(DateTime(2024, 5, 20, 0, 0, 0, "+02:00"), Date(2024, 5, 20).atStartOfDay("+02:00"))

## Calendar time: `DateTime`

`DateTime` represents a point in time in a specific timezone. It provides access to calendar components
such as year, month, day, and hour.

### Constructing

    import lyng.time

    val now = DateTime.now()
    val offsetTime = DateTime.now("+02:00")
    val dt = Instant().toDateTime("Z")
    val dt2 = DateTime(2024, 1, 1, 12, 0, 0, "Z")
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
| date: Date                       | calendar date component                       |
| toInstant(): Instant             | convert back to absolute Instant              |
| toDate(): Date                   | extract the calendar date in this timezone    |
| toUTC(): DateTime                | shortcut to convert to UTC                    |
| toTimeZone(tz): DateTime         | convert to another timezone                   |
| addMonths(n): DateTime           | add/subtract months (normalizes end of month) |
| addYears(n): DateTime            | add/subtract years                            |
| toRFC3339(): String              | format with timezone offset                   |
| static now(tz?): DateTime        | create DateTime with current time             |
| static parseRFC3339(s): DateTime | parse RFC3339 string                          |

### Arithmetic and normalization

`DateTime` handles calendar arithmetic correctly:

    import lyng.time

    val leapDay = Instant("2024-02-29T12:00:00Z").toDateTime("Z")
    val nextYear = leapDay.addYears(1)
    assertEquals(28, nextYear.day)

# `Duration` class

`Duration` represents absolute elapsed time between two instants.

    import lyng.time

    val t1 = Instant()
    delay(1.millisecond)
    val t2 = Instant()

    assert(t2 - t1 >= 1.millisecond)
    assert(t2 - t1 < 100.millisecond)
    >>> void

Duration values can be created from numbers using extensions on `Int` and `Real`:

- `n.millisecond`, `n.milliseconds`
- `n.second`, `n.seconds`
- `n.minute`, `n.minutes`
- `n.hour`, `n.hours`
- `n.day`, `n.days`

Larger units like months or years are calendar-dependent and are intentionally not part of `Duration`.

Each duration instance can be converted to numbers in these units:

- `d.microseconds`
- `d.milliseconds`
- `d.seconds`
- `d.minutes`
- `d.hours`
- `d.days`

Example:

    import lyng.time

    assertEquals(60, 1.minute.seconds)
    assertEquals(10.milliseconds, 0.01.seconds)
    >>> void

# Utility functions

## `delay(duration: Duration)`

Suspends the current coroutine for at least the specified duration.
