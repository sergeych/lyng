# ResultSet typed decode API

Status: draft design note

## Goal

Extend `lyng.io.db` with row deserialization into ordinary Lyng objects using the new typed serialization-style API naming.

Primary use case:

```lyng
class Point(x: Real, y: Real)

val point = db.transaction { tx ->
    tx.select(
        "select row as x, col as y from data where not is_deleted"
    ).decodeAs<Point>().first
}
```

## Agreed API

Use `decodeAs<T>()` as the only public API form in v1.

Rationale:

- matches the new typed serialization naming (`Json.decodeAs(...)`)
- communicates decoding/materialization, not casting
- keeps the common case strongly typed and chain-friendly
- avoids adding a second runtime-type overload before it is needed

Planned Lyng-facing declarations:

```lyng
extern class SqlRow {
    fun decodeAs<T>(): T
}

extern class ResultSet : Iterable<SqlRow> {
    fun decodeAs<T>(): Iterable<T>
}
```

## Lifetime semantics

`ResultSet.decodeAs<T>()` returns a transaction-scoped iterable view over the underlying result set.

Rules:

- the returned iterable must not be used after the owning transaction ends
- decoded objects created during iteration are detached ordinary Lyng objects
- to keep decoded values after the transaction, materialize them inside the transaction
- normal materialization forms are `toList()`, `first`, `findFirst`, or manual iteration

Valid:

```lyng
val points = db.transaction { tx ->
    tx.select("select x, y from point")
        .decodeAs<Point>()
        .toList()
}
```

Invalid:

```lyng
val decoded = db.transaction { tx ->
    tx.select("select x, y from point").decodeAs<Point>()
}

decoded.first
```

## ResultSet shape

`ResultSet.decodeAs<T>()` should preserve the current `ResultSet` paradigm:

- `ResultSet` stays the row-producing source
- `decodeAs<T>()` is a projection from `Iterable<SqlRow>` to `Iterable<T>`
- no new DB-specific collection type is introduced in v1

Implementation-wise, `ResultSet.decodeAs<T>()` can be defined as a lazy iterable that decodes each row via `SqlRow.decodeAs<T>()`.

## Mapping discussion to finalize

The following mapping behavior still needs explicit design decisions:

- how constructor parameters are matched from columns
- whether matching is case-insensitive
- whether mutable serializable fields are populated after constructor call
- treatment of default constructor values
- treatment of nullable vs non-nullable targets
- behavior for missing columns
- behavior for extra columns
- behavior for duplicate/ambiguous column labels
- whether `onDeserialized()` is called after row decode
- whether v1 supports only flat object decode or also nested shapes

## Current direction for mapping

Current likely direction, not finalized yet:

- constructor parameters map by column label
- matching is case-insensitive, consistent with `SqlRow["name"]`
- after constructor call, remaining matching serializable mutable fields may be assigned
- missing required non-null constructor values fail
- missing nullable constructor parameters become `null`
- defaulted constructor parameters use their defaults when the column is absent
- ambiguous duplicate column labels fail
- extra columns likely fail in strict mode for v1
- `onDeserialized()` likely should run after the object is fully populated
- v1 should likely stay flat and avoid nested/prefix-based mapping

## Projection/conversion rules

### General principle

Row decoding should be strict and predictable.

It should not globally treat every SQL string column as serialized JSON or every binary column as Lynon.

That would be too implicit:

- ordinary text columns are common and must stay ordinary text by default
- ordinary binary/blob columns are common and must stay raw binary by default
- automatic format decoding should happen only when there is a clear signal

### Proposed conversion precedence

For each constructor parameter or serializable mutable field:

1. resolve the source column by name
2. if the source value already matches the target type, use it directly
3. if an explicit DB decoding attribute is present on the target member, apply that decoding rule
4. otherwise, if the column metadata clearly indicates a special encoded DB type and the target is not the raw DB carrier type, apply the built-in format rule
5. otherwise fail with a decode/type mismatch error

### Direct match

Direct match means the row value is already assignable to the target type after the normal SQL backend conversion.

Examples:

- SQL numeric column already surfaced as `Int`/`Real`/`Decimal`
- SQL bool column surfaced as `Bool`
- SQL date/time column surfaced as `Date`, `DateTime`, `Instant`
- SQL text column surfaced as `String`
- SQL binary column surfaced as `Buffer`

These should not trigger any extra JSON/Lynon decoding.

### Built-in encoded-column rules

Current likely direction:

- JSON/JSONB-like columns should decode through typed canonical `Json` when the target is not `String`
- binary columns should decode through `Lynon` when the target is not `Buffer`

This implies the current default:

- string -> non-string is eligible for automatic typed `Json` decode only when the column metadata says the DB column is JSON-like
- binary -> non-binary is decoded through `Lynon`
- binary -> `Buffer` stays raw `Buffer`

Examples:

- PostgreSQL `json` / `jsonb` column into `Point` -> use typed `Json` decode
- PostgreSQL `jsonb` column into `Map<String, Object?>` -> use typed `Json` decode
- plain `text` / `varchar` column into `Point` -> fail unless explicitly annotated
- `bytea` / `blob` column into `Buffer` -> direct match, no Lynon decode
- `bytea` / `blob` column into `Point` -> decode with `Lynon`

### Attribute-based explicit decoding

Common explicit attributes look useful:

- `@DbJson`
- `@DbLynon`

Applied to constructor parameters and serializable mutable fields.

Meaning:

- `@DbJson` means decode the column value as typed canonical JSON into the target member type
- `@DbLynon` means decode the column value as Lynon into the target member type

Example:

```lyng
class Record(
    id: Int,
    @DbJson payload: Payload,
    @DbLynon cachedState: CacheEntry
)
```

This keeps the common DB formats easy to use without making plain `String` or `Buffer` columns magical.

Implementation note:

- declaration metadata now preserves evaluated constructor-parameter and class-member annotation arguments
- annotation arguments are evaluated once at declaration creation time and retained for the lifetime of the declaration
- `@DbDecodeWith(...)` now uses that preserved metadata path

### Generic custom decoder hook

A generic hook is useful too, but it should be adapter-based, not lambda-based.

Planned shape:

- `@DbDecodeWith(adapter)`
- `adapter` should be an instance of a dedicated interface such as `DbFieldAdapter`

Reason:

- a named adapter interface is easier to document and evolve than arbitrary callables
- it gives us room for richer decoding context without baking ad-hoc callable signatures into annotations
- it keeps the DB mapping API explicit and self-describing

Current design direction:

```lyng
interface DbFieldAdapter {
    fun decode(rawValue: Object?, column: SqlColumn, row: SqlRow, targetType: Object): Object? =
        throw NotImplementedException("DB field adapter decode is not implemented")

    fun encode(value: Object?, targetType: Object): Object? =
        throw NotImplementedException("DB field adapter encode is not implemented")
}
```

Decided:

- `decode(...)` should receive the target type
- adapters may be any ordinary instance, not only singleton objects
- the same abstraction should later support symmetric `encode(...)`
- adapter result must be checked against the target member type after decoding

Still open before full implementation:

- exact annotation shape for `@DbDecodeWith(...)`
- whether target member name should also be passed
- whether `targetType` should later get a more specific declaration type than plain `Object`

Implemented in the current design:

- `@DbDecodeWith(adapter)` on constructor parameters
- `@DbDecodeWith(adapter)` on class-body fields/properties participating in `decodeAs<T>()`

Future improvement:

- compiler warning when preserved annotation metadata captures runtime state/closures
- extend preserved annotation metadata beyond constructor parameters and class members to functions and top-level declarations

### Arrays and maps

Arrays and maps should not get DB-specific bespoke mapping in v1 unless they are coming through a recognized encoded format.

Reason:

- portable SQL array/map support is backend-specific and inconsistent
- JSON columns already give us a portable representation for `List` and `Map`
- adding DB-native array semantics now would complicate the contract too early

So in v1:

- if the backend already surfaces a value that directly matches the target type, use it
- otherwise `List` / `Map` reconstruction should happen via `@DbJson` or recognized JSON-like column metadata

### Recommended v1 policy

Current recommended projection policy:

- direct type match first
- then explicit member attribute (`@DbJson`, `@DbLynon`)
- then metadata-driven JSON decode for recognized JSON-like DB columns
- then Lynon decode for binary columns when the target is not `Buffer`
- no implicit JSON decode for arbitrary text columns
- fail on anything else
