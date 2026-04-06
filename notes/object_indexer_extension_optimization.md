# Object indexer extension follow-up

Context
- `Obj.getAt` / `Obj.putAt` now support scoped extension indexers for named singleton `object` declarations, so patterns like `Storage["name"]` can route through `override fun Storage.getAt(...)` and `override fun Storage.putAt(...)`.
- Dispatch order is intentionally:
  1. concrete non-root class members
  2. scoped extensions
  3. built-in string-field fallback

Why this order
- Normal class-defined indexers are the common fast path and should not pay extension lookup cost first.
- Checking scoped extensions earlier caused avoidable overhead for regular overridden indexers.
- Routing extension indexers through the generic root `Obj.getAt` member path caused recursion; direct extension dispatch avoids that.

Possible future optimization
- If extension-based indexers show up in hot profiles, add a tiny receiver-shape/member cache at the extension-dispatch points in `Obj.getAt` / `Obj.putAt`.
- The cache should stay behind the concrete non-root member fast path.
- A good shape key is likely `(receiver class id/layout version, member name)` with the cached extension record/wrapper.
