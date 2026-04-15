# Lyng.io.db

Core level interface to SQL implementations. Pooling, physical connections, and implementation-specific configuration are hidden behind the opened database handle.

All providers should support the generic `openDatabase(connectionUrl, extraParams)`
entry point for configuration-driven usage. Providers may also expose typed
helpers such as `openSqlite(...)` or `openPostgres(...)`.

Provider modules should register their URL schemes when first imported. The
generic `openDatabase(...)` then dispatches by normalized URL scheme to the
registered provider.

See [db definitions](lyngdb.lyng).

## Platforms support:

| DB       | JVM | Native |
|----------|-----|--------|
| Postgres | +   | ?      |
| H2       | +   | +      |
| SQLITE   | +   | +      |


Question for the future: what to do on JS platforms? Browsers have their crazy own storages, bit it is not SQL. Probably for this case we need some simpler standard compatible with browsers and with special implementation on JVM.

## Proposed type mapping:

| SQL                                        | Lyng     | comments |
|--------------------------------------------|----------|----------|
| SMALLINT                                   | Int      | Lyng `Int` is already 64-bit |
| INTEGER / INT                              | Int      |          |
| BIGINT                                     | Int      | Lyng `Int` is already 64-bit |
| REAL / FLOAT / DOUBLE PRECISION            | Double   |          |
| DECIMAL / NUMERIC                          | Decimal  | exact numeric |
| BOOLEAN / BOOL                             | Bool     |          |
| CHAR / VARCHAR / TEXT                      | String   |          |
| BINARY / VARBINARY / BLOB / BYTEA          | Buffer   |          |
| DATE                                       | Date     | calendar date |
| TIME / TIME WITHOUT TIME ZONE              | String   | v1: no standalone Lyng `Time` type yet |
| TIMESTAMP / TIMESTAMP WITHOUT TIME ZONE    | DateTime | local calendar-dependent timestamp |
| TIME WITH TIME ZONE                        | String   | v1: preserve exact value textually |
| TIMESTAMP WITH TIME ZONE                   | Instant  | absolute point in time |

Notes:
- `TIME` mappings in v1 should stay textual. A SQL time-of-day value has no
  date component, so mapping it to `DateTime` would require inventing one, and
  mapping it to `Instant` would incorrectly assign absolute-time semantics.
- Because of that, `TIME` and `TIME WITH TIME ZONE` should be exposed as
  `String` in portable code. The original backend type is still available
  through `SqlColumn.nativeType`.
- Name-based `SqlRow` access uses result-column labels and should fail on
  missing or ambiguous names.
- `SqlColumn` should expose both the normalized portable `SqlType` and the
  original backend-reported type name.
- For any non-null cell, the converted row value should match the column's
  portable `SqlType`. Providers should not advertise `SqlType.Date`,
  `SqlType.Decimal`, etc. and then return mismatched raw values for those
  columns.
- If a backend-reported value cannot be converted to the advertised Lyng value
  type for that column, row production should fail with `SqlExecutionException`
  rather than silently degrading to some other visible type.
- `ResultSet` should stay iterable, but also expose `isEmpty()` for cheap
  emptiness checks where possible and `size()` as a separate operation.
- `ResultSet` and all `SqlRow` instances obtained from it are valid only while
  the owning transaction is active. After transaction end, any further row or
  result-set access should fail with `SqlUsageException`, even if the provider
  had buffered data internally.
- Portable SQL parameter values should match the row conversion set: `null`,
  `Bool`, `Int`, `Double`, `Decimal`, `String`, `Buffer`,
  `Date`, `DateTime`, and `Instant`.
- Lyng has a single integer type, `Int`, with 64-bit range. Portable SQL
  integer values should therefore normalize to `Int` rather than exposing
  separate `Short` / `Int` / `Long` categories.
- Portable SQL placeholder syntax is positional `?` only.
- The core exception model should stay small: `DatabaseException`,
  `SqlExecutionException`, `SqlConstraintException`, and `SqlUsageException`,
  plus propagated `RollbackException`.
- `openDatabase(...)` should use `IllegalArgumentException` for malformed
  arguments detected before opening, and `DatabaseException` for runtime open
  failures such as authentication, connectivity, or provider initialization.
- Nested `SqlTransaction.transaction {}` must provide real nested transaction
  semantics, usually via savepoints. If the backend cannot support this, it
  should throw `SqlUsageException`.
- Transaction failure precedence should be:
  - if user code throws and rollback succeeds, rethrow the original exception
  - if user code throws and rollback fails, the original exception stays
    primary and the rollback failure is secondary/suppressed where possible
  - if `RollbackException` was used intentionally and rollback itself fails,
    the rollback failure becomes primary
  - if commit fails after normal block completion, the commit failure is
    primary
- Provider URL schemes should be matched case-insensitively. Duplicate scheme
  registration should fail. Unknown schemes or missing providers should fail
  with `DatabaseException`.
