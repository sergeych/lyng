### lyng.io.db — SQL database access for Lyng scripts

This module provides the portable SQL database contract for Lyng. The current shipped providers are SQLite via `lyng.io.db.sqlite` and a JVM-only JDBC bridge via `lyng.io.db.jdbc`.

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

For JVM JDBC-backed access, install the JDBC provider as well:

```kotlin
import net.sergeych.lyng.EvalSession
import net.sergeych.lyng.Scope
import net.sergeych.lyng.io.db.createDbModule
import net.sergeych.lyng.io.db.jdbc.createJdbcModule

suspend fun bootstrapJdbc() {
    val session = EvalSession()
    val scope: Scope = session.getScope()
    createDbModule(scope)
    createJdbcModule(scope)
    session.eval("""
        import lyng.io.db
        import lyng.io.db.jdbc
    """.trimIndent())
}
```

`createJdbcModule(...)` registers `jdbc:`, `h2:`, `postgres:`, and `postgresql:` for `openDatabase(...)`.

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

JVM JDBC open with H2:

```lyng
import lyng.io.db.jdbc

val db = openH2("mem:demo;DB_CLOSE_DELAY=-1")

val names = db.transaction { tx ->
    tx.execute("create table person(id bigint auto_increment primary key, name varchar(120) not null)")
    tx.execute("insert into person(name) values(?)", "Ada")
    tx.execute("insert into person(name) values(?)", "Linus")
    tx.select("select name from person order by id").toList()
}

assertEquals("Ada", names[0]["name"])
assertEquals("Linus", names[1]["name"])
```

Generic JDBC open through `openDatabase(...)`:

```lyng
import lyng.io.db
import lyng.io.db.jdbc

val db = openDatabase(
    "jdbc:h2:mem:demo2;DB_CLOSE_DELAY=-1",
    Map()
)

val answer = db.transaction { tx ->
    tx.select("select 42 as answer").toList()[0]["answer"]
}

assertEquals(42, answer)
```

PostgreSQL typed open:

```lyng
import lyng.io.db.jdbc

val db = openPostgres(
    "jdbc:postgresql://127.0.0.1/appdb",
    "appuser",
    "secret"
)

val titles = db.transaction { tx ->
    tx.execute("create table if not exists task(id bigserial primary key, title text not null)")
    tx.execute("insert into task(title) values(?)", "Ship JDBC provider")
    tx.execute("insert into task(title) values(?)", "Test PostgreSQL path")
    tx.select("select title from task order by id").toList()
}

assertEquals("Ship JDBC provider", titles[0]["title"])
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

`select(...)` and `execute(...)` also support SQL object-expansion macros for declaration-driven writes:

- `@cols(?1)` — expand object argument `?1` to a comma-separated column list
- `@vals(?1)` — expand object argument `?1` to matching placeholders and bind values
- `@set(?1)` — expand object argument `?1` to `column = ?` pairs and bind values

Each macro also supports an optional clause-local exclusion list:

```lyng
tx.execute("update item set @set(?1 except: \"id\", \"createdAt\") where id = ?2", item, item.id)
```

Example:

```lyng
tx.execute("insert into item(@cols(?1)) values(@vals(?1))", item)
tx.execute("update item set @set(?1) where id = ?2", item, item.id)
```

When a clause uses any of these macros, non-expanded scalar parameters in the same SQL string must use explicit indexed placeholders such as `?2`, `?3`, and so on.

##### `ResultSet`

- `columns` — positional `SqlColumn` metadata, available before iteration.
- `size()` — result row count.
- `isEmpty()` — fast emptiness check where possible.
- `iterator()` — normal row iteration while the transaction is active.
- `toList()` — materialize detached `SqlRow` snapshots that may be used after the transaction ends.
- `decodeAs<T>()` — transaction-scoped iterable view that decodes each row into `T`.

##### `SqlRow`

- `row[index]` — zero-based positional access.
- `row["columnName"]` — case-insensitive lookup by output column label.
- `row.decodeAs<T>()` — decode one row into a typed Lyng value.

Name-based access fails with `SqlUsageException` if the name is missing or ambiguous.

##### `DbFieldAdapter`

Custom DB field projection hook used by `@DbDecodeWith(...)` and `@DbSerializeWith(...)`.

- `decode(rawValue, column, row, targetType)` — adapt one raw DB field value to a Lyng value for the requested target type.
- `encode(value, targetType)` — adapt one Lyng value to a direct DB-bindable value for SQL object expansion.

Use `@DbDecodeWith(adapter)` on class constructor parameters and class-body fields/properties that participate in `decodeAs<T>()`.

Use `@DbSerializeWith(adapter)` on constructor parameters and class-body fields/properties that participate in `@cols(...)`, `@vals(...)`, and `@set(...)` object expansion.

Annotation arguments are evaluated once when the declaration is created, and the resulting adapter instance is retained in declaration metadata.

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

SQL object-expansion write rules:

- constructor parameters participate in projection by declaration order
- matching serializable class-body fields/properties also participate
- `@Transient` fields are excluded automatically
- `@DbExcept` fields are excluded automatically
- `except:` excludes additional fields for one specific macro use
- direct DB-bindable values are written as-is
- `@DbJson` fields are encoded as canonical JSON text
- `@DbLynon` fields are encoded as Lynon binary
- `@DbSerializeWith(adapter)` fields are encoded through the adapter
- unannotated non-bindable object fields fail with `SqlUsageException`

Write-side encoding is intentionally explicit. The runtime does not try to infer target DB column types from SQL text or backend metadata during statement preparation.

Example:

```lyng
import lyng.io.db
import lyng.io.db.sqlite

class Payload(name: String, count: Int)

object TrimAdapter: DbFieldAdapter {
    override fun encode(value, targetType) =
        when(value) {
            null -> null
            else -> value.toString().trim()
        }
}

class Item(
    id: Int,
    @DbSerializeWith(TrimAdapter) title: String,
    @DbJson meta: Payload,
    @DbLynon state: Payload
) {
    var note: String = ""
    @DbExcept var cache: String = ""
}

val db = openSqlite(":memory:")
val restored = db.transaction { tx ->
    tx.execute(
        "create table item(id integer not null, title text not null, meta text not null, state blob not null, note text not null)"
    )

    val item = Item(1, "  first  ", Payload("json", 10), Payload("bin", 20))
    item.note = "created"
    item.cache = "not stored"

    tx.execute("insert into item(@cols(?1)) values(@vals(?1))", item)

    item.title = "  second  "
    item.meta = Payload("json2", 11)
    item.state = Payload("bin2", 21)
    item.note = "updated"

    tx.execute(
        "update item set @set(?1 except: \"id\") where id = ?2",
        item,
        item.id
    )

    tx.select("select id, title, meta, state, note from item").decodeAs<Item>().first
}

assertEquals("second", restored.title)
assertEquals("json2", restored.meta.name)
assertEquals(21, restored.state.count)
assertEquals("updated", restored.note)
```

This example shows:

- `@DbSerializeWith(...)` trimming a string before write
- `@DbJson` storing structured data in a text column
- `@DbLynon` storing structured data in a binary column
- `@DbExcept` excluding a field from automatic projection
- `@set(... except: "id")` skipping one field for an update clause
- `decodeAs<Item>()` reconstructing the object on read

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

Typed row decode rules:

- object/class targets map constructor parameters by column label, case-insensitively
- remaining matching serializable mutable fields are assigned after constructor call
- `@DbDecodeWith(adapter)` on a constructor parameter or class-body field/property takes precedence over built-in JSON/Lynon decoding
- `@DbDecodeWith(adapter)` must receive exactly one adapter instance implementing `DbFieldAdapter`
- adapter output must match the target member type or decoding fails with `SqlUsageException`
- missing required non-null constructor fields fail
- defaulted or nullable constructor fields may be omitted from the result
- extra result columns currently fail in strict mode
- if a row has exactly one column, that value may be decoded directly as the requested target type
- JSON-like native column types (`json`, `jsonb`) are decoded through typed canonical `Json` when the target type is not `String`
- binary columns are decoded through `Lynon` when the target type is not `Buffer`
- `Buffer` targets keep the raw binary payload without Lynon decoding
- plain text columns are not implicitly treated as JSON

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

#### JDBC provider

`lyng.io.db.jdbc` is currently implemented on the JVM target only. The `lyngio-jvm` artifact bundles and explicitly loads these JDBC drivers:

- SQLite
- H2
- PostgreSQL

Typed helpers:

```lyng
openJdbc(
    connectionUrl: String,
    user: String? = null,
    password: String? = null,
    driverClass: String? = null,
    properties: Map<String, Object?>? = null
): Database

openH2(
    connectionUrl: String,
    user: String? = null,
    password: String? = null,
    properties: Map<String, Object?>? = null
): Database

openPostgres(
    connectionUrl: String,
    user: String? = null,
    password: String? = null,
    properties: Map<String, Object?>? = null
): Database
```

Accepted generic URL forms:

- `jdbc:h2:mem:test;DB_CLOSE_DELAY=-1`
- `h2:mem:test;DB_CLOSE_DELAY=-1`
- `jdbc:postgresql://localhost/app`
- `postgres://localhost/app`
- `postgresql://localhost/app`

Supported `openDatabase(..., extraParams)` keys for JDBC:

- `driverClass: String`
- `user: String`
- `password: String`
- `properties: Map<String, Object?>`

Behavior notes for the JDBC bridge:

- the portable `Database` / `SqlTransaction` API stays the same as for SQLite
- nested transactions use JDBC savepoints
- JDBC connection properties are built from `user`, `password`, and `properties`
- `properties` values are stringified before being passed to JDBC
- statements with row-returning clauses still must use `select(...)`, not `execute(...)`

Platform support for this provider:

- `lyng.io.db.jdbc` — JVM only
- `openH2(...)` — works out of the box with `lyngio-jvm`
- `openPostgres(...)` — driver included, but an actual PostgreSQL server is still required

PostgreSQL-specific notes:

- `openPostgres(...)` accepts either a full JDBC URL or shorthand forms such as `//localhost/app`
- local peer/trust setups may use an empty password string
- generated keys work with PostgreSQL `bigserial` / identity columns through `ExecutionResult.getGeneratedKeys()`
- for reproducible automated tests, prefer a disposable PostgreSQL instance such as Docker/Testcontainers instead of a long-lived shared server

---

#### Lifetime rules

`ResultSet` is valid only while its owning transaction is active.

`SqlRow` values are detached snapshots once materialized, so this pattern is valid:

```lyng
val rows = db.transaction { tx ->
    tx.select("select name from person order by id").toList()
}

assertEquals("Ada", rows[0]["name"])
```

This means:

- do not keep `ResultSet` objects after the transaction block returns
- materialize rows with `toList()` inside the transaction when they must outlive it
- the iterable returned by `decodeAs<T>()` is also transaction-scoped
- decoded objects produced while iterating `decodeAs<T>()` are detached ordinary Lyng values

The same rule applies to generated keys from `ExecutionResult.getGeneratedKeys()`: the `ResultSet` is transaction-scoped, but rows returned by `toList()` are detached.

---

#### Platform support

- `lyng.io.db` — generic contract, available when host code installs it
- `lyng.io.db.sqlite` — implemented on JVM and Linux Native in the current release tree
- `lyng.io.db.jdbc` — implemented on JVM in the current release tree

For the broader I/O overview, see [lyngio overview](lyngio.md).
