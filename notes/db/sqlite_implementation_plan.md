# SQLite provider implementation plan

Implementation checklist for the first concrete DB provider:
- module: `lyng.io.db.sqlite`
- targets: JVM and Native
- role: reference implementation for the core `lyng.io.db` contract

## Scope

In scope for the first implementation:
- provider registration on module import
- `sqlite:` URL dispatch through `openDatabase(...)`
- typed `openSqlite(...)` helper
- `Database` and `SqlTransaction` implementation
- result-set implementation
- row and column metadata conversion
- SQLite savepoint-based nested transactions
- generated keys for `execute(...)`
- JVM and Native test coverage for the core portable contract

Out of scope for the first implementation:
- schema inspection APIs beyond result-set column metadata
- public prepared statement API
- batch API
- JS support
- heuristic temporal parsing
- heuristic decimal parsing

## Proposed module layout

Core/public declarations:
- `.lyng` declaration source for `lyng.io.db`
- `.lyng` declaration source for `lyng.io.db.sqlite`

Backend/runtime implementation:
- common transaction/result-set abstractions in `commonMain` where possible
- JVM SQLite implementation in `jvmMain`
- Native SQLite implementation in `nativeMain`

## Milestone 1: core DB module skeleton

1. Move or copy the current DB declarations from `notes/db/lyngdb.lyng` into the
   real module declaration source location.
2. Add the provider registry runtime for:
   - normalized lowercase scheme lookup
   - duplicate registration failure
   - unknown-scheme failure
3. Implement generic `openDatabase(connectionUrl, extraParams)` dispatch.
4. Add basic tests for:
   - successful provider registration
   - duplicate scheme registration failure
   - unknown scheme failure
   - malformed URL failure

## Milestone 2: SQLite provider skeleton

1. Create `lyng.io.db.sqlite` declaration source with:
   - typed `openSqlite(...)`
   - provider-specific documentation
2. Register `sqlite` scheme at module initialization time.
3. Parse supported URL forms:
   - `sqlite::memory:`
   - `sqlite:relative/path.db`
   - `sqlite:/absolute/path.db`
4. Convert typed helper arguments into the same normalized open options used by
   the generic `openDatabase(...)` path.
5. Add tests for:
   - import-time registration
   - typed helper open
   - generic URL-based open
   - invalid URL handling

## Milestone 3: JVM backend

Implementation strategy:
- use SQLite JDBC under the hood
- keep JDBC fully internal to the provider
- preserve the Lyng-facing transaction/result contracts rather than exposing
  JDBC semantics directly

Steps:
1. Create JVM `Database` implementation that stores normalized SQLite config.
2. For each outer `Database.transaction {}`:
   - open/acquire one JDBC connection
   - configure connection-level options
   - begin transaction
   - commit on normal exit
   - rollback on uncaught exception
   - close/release connection
   - preserve error precedence:
     - original user exception stays primary on rollback failure
     - rollback failure becomes primary for intentional `RollbackException`
     - commit failure is primary on normal-exit commit failure
3. For nested `SqlTransaction.transaction {}`:
   - create savepoint
   - release savepoint on success
   - rollback to savepoint on failure
   - preserve the same primary/secondary exception rules for savepoint rollback
     failures
4. Implement `select(...)`:
   - bind positional `?` parameters
   - expose result rows through `ResultSet`
   - preserve transaction-scoped lifetime
   - invalidate both the result set and any rows obtained from it when the
     owning transaction ends
5. Implement `execute(...)`:
   - bind positional parameters
   - collect affected row count
   - expose generated keys when supported
6. Implement column metadata normalization:
   - output column label
   - nullable flag where available
   - portable `SqlType`
   - backend native type name
7. Add JVM tests for:
   - transaction commit
   - transaction rollback
   - nested transaction savepoint behavior
   - rollback failure precedence
   - commit failure precedence
   - row lookup by index and name
   - ambiguous name failure
   - result-set use-after-transaction failure
   - row use-after-transaction failure
   - generated keys
   - `RETURNING` via `select(...)` if the backend supports it

## Milestone 4: Native backend

Implementation strategy:
- use direct SQLite C bindings
- keep semantics aligned with the JVM backend

Steps:
1. Create Native `Database` implementation that stores normalized SQLite config.
2. For each outer transaction:
   - open one SQLite handle if needed
   - configure pragmas/options
   - begin transaction
   - commit or rollback
3. Implement nested transactions with SQLite savepoints.
4. Implement prepared statement lifecycle internally for `select(...)` and
   `execute(...)`.
5. Implement result-set iteration and statement finalization.
6. Implement the same value-conversion policy as JVM:
   - exact integer mapping
   - `Double`
   - `String`
   - `Buffer`
   - schema-driven `Bool`
   - schema-driven `Decimal`
   - schema-driven temporal parsing
7. Add Native tests matching the JVM behavioral suite as closely as possible.

## Milestone 5: conversion policy

1. Normalize integer reads:
   - return `Int`
2. Normalize floating-point reads to `Double`.
3. Normalize text reads to `String`.
4. Normalize blob reads to `Buffer`.
5. Parse `Decimal` only for declared/native `DECIMAL` / `NUMERIC` columns.
   - bind Lyng `Decimal` as canonical text using existing Decimal formatting
   - read back with the existing Decimal parser
6. Parse `Bool` only for declared/native `BOOLEAN` / `BOOL` columns:
   - prefer integer `0` / `1`
   - also accept legacy text `true`, `false`, `t`, `f` case-insensitively
   - always write `Bool` as integer `0` / `1`
7. Parse temporal values only from an explicit normalized type-name whitelist:
   - `DATE`
   - `DATETIME`
   - `TIMESTAMP`
   - `TIMESTAMP WITH TIME ZONE`
   - `TIMESTAMPTZ`
   - `DATETIME WITH TIME ZONE`
   - `TIME`
   - `TIME WITHOUT TIME ZONE`
   - `TIME WITH TIME ZONE`
8. Never heuristically parse arbitrary string or numeric values into temporal or
   decimal values.
9. If a declared strong type (`Bool`, `Decimal`, `Date`, `DateTime`, `Instant`)
   cannot be converted from the stored value, fail with `SqlExecutionException`.
10. Add tests for each conversion rule.

## Milestone 6: result-set contract

1. Ensure `ResultSet.columns` is available before iteration.
2. Implement name lookup using result-column labels.
3. Throw `SqlUsageException` for:
   - invalid row index
   - missing column name
   - ambiguous column name
   - use after transaction end
4. Implement cheap `isEmpty()` where practical.
5. Implement `size()` separately, allowing buffering/consumption when required.
6. Verify resource cleanup on:
   - full iteration
   - canceled iteration
   - transaction rollback

## Milestone 7: provider options

Support these typed helper options first:
- `readOnly`
- `createIfMissing`
- `foreignKeys`
- `busyTimeoutMillis`

Expected behavior:
- `foreignKeys` defaults to `true`
- `busyTimeoutMillis` has a non-zero sensible default
- `readOnly` is explicit
- `createIfMissing` is explicit

Add tests for option handling where backend behavior is observable.

## Milestone 8: documentation and examples

1. Add user docs for:
   - importing `lyng.io.db.sqlite`
   - generic `openDatabase(...)`
   - typed `openSqlite(...)`
   - transaction usage
   - nested transactions
2. Add small sample snippets for:
   - in-memory DB
   - file-backed DB
   - schema creation
   - insert/select/update
   - rollback on exception

## Testing priorities

Highest priority behavioral tests:
1. provider registration and scheme dispatch
2. outer transaction commit/rollback
3. nested savepoint semantics
4. row metadata and name lookup behavior
5. generated keys for inserts
6. result-set lifetime rules
7. value conversion rules
8. helper vs generic-open parity

## Risks

Main implementation risks:
- keeping JVM and Native value-conversion behavior identical
- correctly enforcing result-set lifetime across both backends
- SQLite temporal conversion ambiguity
- JDBC metadata differences on JVM
- native statement/finalizer lifecycle bugs

## Suggested implementation order

1. core registry runtime
2. SQLite `.lyng` provider declarations
3. JVM SQLite backend
4. JVM test suite
5. Native SQLite backend
6. shared behavioral test suite refinement
7. documentation/examples
