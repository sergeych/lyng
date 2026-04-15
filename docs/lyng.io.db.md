### lyng.io.db — SQL database access for Lyng scripts

This module provides the portable SQL database contract for Lyng. The first shipped provider is SQLite via `lyng.io.db.sqlite`.

> **Note:** `lyngio` is a separate library module. It must be explicitly added as a dependency to your host application and initialized in your Lyng scopes.

---

#### Install the module into a Lyng session

For SQLite-backed database access, install both the generic DB module and the SQLite provider:

```kotlin
import net.sergeych.lyng.EvalSession
import net.sergeych.lyng.Scope
import net.sergeych.lyng.io.db.createDbModule
import net.sergeych.lyng.io.db.sqlite.createSqliteModule

suspend fun bootstrapDb() {
    val session = EvalSession()
    val scope: Scope = session.getScope()
    createDbModule(scope)
    createSqliteModule(scope)
    session.eval("""
        import lyng.io.db
        import lyng.io.db.sqlite
    """.trimIndent())
}
```

`createSqliteModule(...)` also registers the `sqlite:` scheme for generic `openDatabase(...)`.

---

#### Using from Lyng scripts

Typed SQLite open helper:

```lyng
import lyng.io.db.sqlite

val db = openSqlite(":memory:")

val userCount = db.transaction { tx ->
    tx.execute("create table user(id integer primary key autoincrement, name text not null)")
    tx.execute("insert into user(name) values(?)", "Ada")
    tx.execute("insert into user(name) values(?)", "Linus")
    tx.select("select count(*) as count from user").toList()[0]["count"]
}

assertEquals(2, userCount)
```

Generic provider-based open:

```lyng
import lyng.io.db
import lyng.io.db.sqlite

val db = openDatabase(
    "sqlite:./app.db",
    Map(
        "foreignKeys" => true,
        "busyTimeoutMillis" => 5000
    )
)
```

Nested transactions use real savepoint semantics:

```lyng
import lyng.io.db
import lyng.io.db.sqlite

val db = openSqlite(":memory:")

db.transaction { tx ->
    tx.execute("create table item(id integer primary key autoincrement, name text not null)")
    tx.execute("insert into item(name) values(?)", "outer")

    try {
        tx.transaction { inner ->
            inner.execute("insert into item(name) values(?)", "inner")
            throw IllegalStateException("rollback nested")
        }
    } catch (_: IllegalStateException) {
    }

    assertEquals(1, tx.select("select count(*) as count from item").toList()[0]["count"])
}
```

Intentional rollback without treating it as a backend failure:

```lyng
import lyng.io.db
import lyng.io.db.sqlite

val db = openSqlite(":memory:")

assertThrows(RollbackException) {
    db.transaction { tx ->
        tx.execute("create table item(id integer primary key autoincrement, name text not null)")
        tx.execute("insert into item(name) values(?)", "temporary")
        throw RollbackException("stop here")
    }
}
```

---

#### Portable API

##### `Database`

- `transaction(block)` — opens a transaction, commits on normal exit, rolls back on uncaught failure.

##### `SqlTransaction`

- `select(clause, params...)` — execute a statement whose primary result is a row set.
- `execute(clause, params...)` — execute a side-effect statement and return `ExecutionResult`.
- `transaction(block)` — nested transaction with real savepoint semantics.

##### `ResultSet`

- `columns` — positional `SqlColumn` metadata, available before iteration.
- `size()` — result row count.
- `isEmpty()` — fast emptiness check where possible.
- `iterator()` / `toList()` — normal row iteration.

##### `SqlRow`

- `row[index]` — zero-based positional access.
- `row["columnName"]` — case-insensitive lookup by output column label.

Name-based access fails with `SqlUsageException` if the name is missing or ambiguous.

##### `ExecutionResult`

- `affectedRowsCount`
- `getGeneratedKeys()`

Statements that return rows directly, such as `... returning ...`, should use `select(...)`, not `execute(...)`.

---

#### Value mapping

Portable bind values:

- `null`
- `Bool`
- `Int`, `Double`, `Decimal`
- `String`
- `Buffer`
- `Date`, `DateTime`, `Instant`

Unsupported parameter values fail with `SqlUsageException`.

Portable result metadata categories:

- `Binary`
- `String`
- `Int`
- `Double`
- `Decimal`
- `Bool`
- `Date`
- `DateTime`
- `Instant`

For temporal types, see [time functions](time.md).

---

#### SQLite provider

`lyng.io.db.sqlite` currently provides the first concrete backend.

Typed helper:

```lyng
openSqlite(
    path: String,
    readOnly: Bool = false,
    createIfMissing: Bool = true,
    foreignKeys: Bool = true,
    busyTimeoutMillis: Int = 5000
): Database
```

Accepted generic URL forms:

- `sqlite::memory:`
- `sqlite:relative/path.db`
- `sqlite:/absolute/path.db`

Supported `openDatabase(..., extraParams)` keys for SQLite:

- `readOnly: Bool`
- `createIfMissing: Bool`
- `foreignKeys: Bool`
- `busyTimeoutMillis: Int`

SQLite write/read policy in v1:

- `Bool` writes as `0` / `1`
- `Decimal` writes as canonical text
- `Date` writes as `YYYY-MM-DD`
- `DateTime` writes as ISO local timestamp text without timezone
- `Instant` writes as ISO UTC timestamp text with explicit timezone marker
- `TIME*` values stay `String`
- `TIMESTAMP` / `DATETIME` reject timezone-bearing stored text

Open-time validation failures:

- malformed URL or bad option shape -> `IllegalArgumentException`
- runtime open failure -> `DatabaseException`

---

#### Lifetime rules

Result sets and rows are valid only while their owning transaction is active.

This means:

- do not keep `ResultSet` or `SqlRow` objects after the transaction block returns
- copy the values you need into ordinary Lyng objects inside the transaction

The same lifetime rule applies to generated keys returned by `ExecutionResult.getGeneratedKeys()`.

---

#### Platform support

- `lyng.io.db` — generic contract, available when host code installs it
- `lyng.io.db.sqlite` — implemented on JVM and Linux Native in the current release tree

For the broader I/O overview, see [lyngio overview](lyngio.md).
