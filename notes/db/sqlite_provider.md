# SQLite provider for `lyng.io.db`

First concrete provider candidate for the DB module.

Module name:
- `lyng.io.db.sqlite`

Responsibilities:
- register SQLite URL schemes on first import
- provide the generic `openDatabase(...)` entry point for SQLite URLs
- provide typed SQLite-specific helpers for ergonomic opening
- implement the core `Database` / `SqlTransaction` API on both JVM and Native

## Registration and URL schemes

On first import, the module should register at least:
- `sqlite`

Possible accepted URL forms:
- `sqlite::memory:`
- `sqlite:./local.db`
- `sqlite:/absolute/path/data.db`

The exact accepted path grammar can be tightened during implementation, but it
should stay simple and configuration-friendly.

## Typed helper

The provider should also expose a typed helper, e.g.:

```lyng
fun openSqlite(
    path: String,
    readOnly: Bool = false,
    createIfMissing: Bool = true,
    foreignKeys: Bool = true,
    busyTimeoutMillis: Int = 5000
): Database
```

Possible special values:
- `":memory:"` for in-memory DB

The helper is provider-specific sugar with explicit typed arguments. The generic
`openDatabase(...)` path must stay fully supported for configuration-driven
usage.

## Implementation strategy

SQLite should be the first real provider because it is available on both JVM and
Native and exercises almost the whole core API surface.

### JVM

Preferred implementation:
- JDBC-backed SQLite provider for the JVM-specific backend implementation

This is acceptable because SQLite itself is local and the JDBC bridge is much
simpler here than for network databases.

### Native

Preferred implementation:
- direct SQLite C library binding

The provider should present the same Lyng-facing semantics on both backends.

## Transactions

Required behavior:
- `Database.transaction {}` starts a real SQLite transaction
- `SqlTransaction.transaction {}` uses SQLite savepoints
- nested transactions must be supported
- failures in nested transactions roll back only to the nested savepoint unless
  the exception escapes further
- error precedence follows the core DB contract:
  - user exception + successful rollback -> user exception escapes unchanged
  - user exception + rollback failure -> user exception stays primary
  - intentional `RollbackException` + rollback failure -> rollback failure is
    primary
  - commit failure after normal completion -> commit failure is primary

SQLite is a good fit here because savepoints are well-supported.

Connection/handle semantics:
- one outer `Database.transaction {}` must use exactly one physical SQLite
  connection/handle for its whole lifetime
- nested transactions must stay on that same connection/handle
- a transaction must never hop across connections

This is required because SQLite transaction state, savepoints, and generated
row-id behavior are all connection-local.

## Result sets

The provider may stream rows or buffer them, but it must preserve the core
contract:
- result sets are valid only while the owning transaction is active
- rows obtained from a result set are also invalid after the owning
  transaction ends, even if they were already buffered
- iteration closes underlying resources when finished or canceled
- `isEmpty()` should be cheap where possible
- `size()` may consume or buffer the full result

## SQLite-specific type mapping notes

SQLite uses dynamic typing and affinity rules, so the provider must normalize
returned values into the portable Lyng types.

Recommended mapping strategy:
- integer values -> `Int`
- floating-point values -> `Double`
- text values -> `String`
- blob values -> `Buffer`
- declared/native `BOOLEAN` / `BOOL` -> `Bool`
- numeric values that are explicitly read/declared as decimal -> `Decimal` when
  the provider can determine this reliably
- date/time values should be parsed only when the declared/native column type
  indicates temporal intent

The provider should not heuristically parse arbitrary `TEXT`, `INTEGER`, or
`REAL` values into temporal or decimal types just because the stored value looks
like one.

If a column is exposed with a stronger portable `SqlType` such as `Bool`,
`Decimal`, `Date`, `DateTime`, or `Instant`, then the produced row value should
either be that Lyng value or `null`. Invalid stored representations should fail
with `SqlExecutionException`.

Boolean policy exception:
- unlike temporal values, legacy boolean encodings are cheap to recognize and
  have low ambiguity
- therefore SQLite `BOOL` / `BOOLEAN` columns may accept a small tolerant set of
  legacy boolean encodings on read
- writes should still always use integer `0` / `1`
- temporal and decimal conversions remain strict/schema-driven

Declared type-name normalization for SQLite v1:
- trim surrounding whitespace
- uppercase
- collapse internal whitespace runs to a single space
- strip a trailing `( ... )` size/precision suffix before matching

Examples:
- ` numeric(10,2) ` -> `NUMERIC`
- `timestamp   with   time zone` -> `TIMESTAMP WITH TIME ZONE`

SQLite v1 declared type-name whitelist:

| normalized declared/native type | portable `SqlType` |
|---------------------------------|--------------------|
| `BOOLEAN`                       | `Bool`             |
| `BOOL`                          | `Bool`             |
| `DECIMAL`                       | `Decimal`          |
| `NUMERIC`                       | `Decimal`          |
| `DATE`                          | `Date`             |
| `DATETIME`                      | `DateTime`         |
| `TIMESTAMP`                     | `DateTime`         |
| `TIMESTAMP WITH TIME ZONE`      | `Instant`          |
| `TIMESTAMPTZ`                   | `Instant`          |
| `DATETIME WITH TIME ZONE`       | `Instant`          |
| `TIME`                          | `String`           |
| `TIME WITHOUT TIME ZONE`        | `String`           |
| `TIME WITH TIME ZONE`           | `String`           |

Anything not in this table should not be promoted to a stronger portable type
just from its declared name.

## SQLite temporal policy

SQLite has no strong built-in temporal storage types, so the provider should use
a strict schema-driven conversion policy.

Binding:
- `null` -> SQL `NULL`
- `Bool` -> integer `0` / `1`
- `Int` -> SQLite integer
- `Double` -> SQLite real
- `Decimal` -> canonical decimal text representation using the existing Lyng
  Decimal formatter
- `String` -> text
- `Buffer` -> blob
- `Date` -> ISO text `YYYY-MM-DD`
- `DateTime` -> ISO text without timezone
- `Instant` -> ISO text in UTC with explicit timezone marker

Reading:
- storage class `NULL` -> `null`
- normalized declared/native type `BOOLEAN` or `BOOL` -> parse as `Bool` with
  this ordered rule:
  - integer `0` / `1` first
  - then legacy text forms, case-insensitively: `true`, `false`, `t`, `f`
  - other stored values are conversion errors
- normalized declared/native type `DATE` -> parse as `Date`
- normalized declared/native type `DATETIME` or `TIMESTAMP` -> parse as `DateTime`
- normalized declared/native type `TIMESTAMP WITH TIME ZONE`,
  `TIMESTAMPTZ`, or `DATETIME WITH TIME ZONE` -> parse as `Instant`
- normalized declared/native type `TIME`, `TIME WITHOUT TIME ZONE`, or
  `TIME WITH TIME ZONE` -> keep as `String` in v1
- normalized declared/native type `DECIMAL` or `NUMERIC` -> parse as `Decimal`
- otherwise integer storage -> `Int`
- otherwise real storage -> `Double`
- otherwise text storage -> `String`
- otherwise blob storage -> `Buffer`
- otherwise do not guess, and return the raw normalized SQLite value type

For v1, the provider should not automatically interpret numeric epoch values or
Julian date encodings unless this is later added as an explicit provider option.

## SQLite decimal policy

Decimal conversion should also be schema-driven:
- normalized declared/native type `DECIMAL` or `NUMERIC` -> parse as `Decimal`
- otherwise do not guess from text or floating-point storage alone

Decimal exactness note:
- SQLite has no native decimal storage class; values are stored as `INTEGER`,
  `REAL`, `TEXT`, `BLOB`, or `NULL`
- binding Lyng `Decimal` as text is only the provider's chosen encoding, not a
  native SQLite decimal representation
- SQLite v1 should therefore store Lyng `Decimal` values as canonical text and
  parse them back with the existing Lyng Decimal parser/formatter stack
- schemas that care about decimal semantics should still declare
  `DECIMAL` / `NUMERIC` affinity so the provider knows to expose `Decimal`
- exact round-tripping therefore cannot be guaranteed for generic
  `DECIMAL` / `NUMERIC` columns, because SQLite affinity rules may coerce stored
  values before they are read back
- SQLite values already stored as `REAL` in `DECIMAL` / `NUMERIC` columns may
  already reflect floating-point precision loss before Lyng sees them
- if exact decimal preservation is required, the schema and provider policy must
  intentionally store decimal values in an exact representation, most simply as
  canonical text

This area will need careful implementation rules because SQLite itself does not
have a strong native temporal type system.

## Generated keys

`ExecutionResult.getGeneratedKeys()` for SQLite should return implementation-
supported generated values for `execute(...)`.

Typical example:
- row id generated by an insert into a table with integer primary key

Statements that explicitly return rows should still go through `select(...)`,
for example if the provider eventually supports SQLite `RETURNING`.

## Options

Likely provider-specific options:
- read-only mode
- create-if-missing
- busy timeout
- foreign keys on/off

These options can be accepted both through SQLite helper functions and through
`openDatabase(..., extraParams)` when the URL scheme is `sqlite`.

Recommended defaults:
- foreign keys enabled by default
- busy timeout may be configurable, but should have a sensible default
- read-only and create-if-missing should be explicit options rather than hidden
  URL magic

## Non-goals for v1

Not required for the first SQLite provider:
- schema metadata beyond result-column metadata
- prepared statement API in public surface
- batch execution API
- provider capability flags
- JS/browser support
