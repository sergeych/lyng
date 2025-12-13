### Lynon class-name resolution: current behavior and roadmap for fully-qualified names

#### Current behavior (as of Dec 2025)

- The Lynon encoder writes the class name for user-defined objects as a simple name taken from `obj.objClass.classNameObj` (e.g., `"Vault"`). It does not encode the fully-qualified path (e.g., `"ns.sub.Vault"`).
- During decoding, `LynonDecoder.decodeClassObj` must resolve this simple name to an `ObjClass` using the provided `Scope`.
- Historically, `decodeClassObj` relied only on `scope.get(name)`. In some scopes this missed symbols that were import-visible at compile time, while `scope.eval(name)` could still find them. This mismatch caused occasional failures such as:
  - `scope.eval("Vault")` ← returns `ObjClass`
  - `scope.get("Vault")` ← returns `null`

To address this, the decoder now uses a robust, import-aware resolution strategy:
1) Try `scope.get(name)`
2) Try `scope.chainLookupWithMembers(name)`
3) Find the nearest `ModuleScope` and check its locals and its parent/root locals/members
4) Check `scope.currentImportProvider.rootScope` globals (e.g., stdlib)
5) As a last resort, try `scope.eval(name)` and validate the result is an `ObjClass`

This resolves the previously observed mismatch, including simple names like `"Vault"` defined in modules or visible via imports.

Notes:
- Tests confirm that the simple-name path is stable after this change.
- Tests for fully qualified names were avoided because the encoder does not currently emit qualified names, and runtime namespaces (like a value-bound `ns`) are not guaranteed to exist for `eval("ns.sub.Vault")` in all scopes.

#### Limitations

- Decoder receives only simple names. If two different classes with the same simple name exist in different packages/modules, decoding could be ambiguous unless the surrounding scope makes the intended one discoverable.
- Using `eval` as last fallback implies compilation at decode-time in rare cases. While this is robust, a purely structural resolution is preferable for determinism and performance.

#### Roadmap: support fully-qualified class names

If/when we decide to encode fully-qualified names, we should implement both encoder and decoder changes in lockstep.

Proposed plan:

1) Encoder changes
   - When serializing a user-defined object, emit the fully-qualified class path (e.g., `"ns.sub.Vault"`) alongside or instead of the simple class name.
   - Consider a feature flag or versioning in Lynon type records to maintain backward compatibility with existing streams that carry only the simple name.

2) Decoder changes
   - If the encoded name contains dots, treat it as a dotted path. Implement a path resolver that:
     - Splits the name by `.` into segments: `[ns, sub, Vault]`.
     - Resolves the first segment with the same multi-scope strategy used today for simple names (steps 1–4 above), without relying on `eval`.
     - For remaining segments, traverse members/namespace tables:
       - If the current object is an `ObjNamespace` or similar container, look up the next segment in its members map.
       - If the current object is an `ObjClass`, look up the next segment with `getInstanceMemberOrNull` (for nested classes or class-level members), if applicable.
     - Continue until the final segment is reached; verify the target is an `ObjClass`.
   - If this structural path resolution fails, optionally fall back to `scope.eval(fullyQualifiedName)` in development modes, but prefer to avoid it in production for determinism.

3) Runtime namespace objects (optional but recommended)
   - Introduce or formalize an `ObjNamespace` (or reuse existing) that represents packages/modules at runtime and can be placed in scope bindings, enabling deterministic traversal of `ns.sub` without compiling code.
   - Ensure imports or `package` declarations materialize such namespace objects in accessible scopes when required, so dotted paths can be resolved purely structurally by the decoder.

4) Backward compatibility and migration
   - Maintain decoding support for simple names for older data.
   - Prefer fully-qualified names when writing new data once the feature is available.
   - Provide configuration to control which form to emit.

5) Testing strategy
   - Add tests that:
     - Encode/decode with simple names (legacy) and confirm existing behavior.
     - Encode/decode with fully-qualified names (new) and confirm dotted-path traversal works without `eval`.
     - Cover ambiguity resolution when same simple name exists in multiple namespaces.

#### Summary

Today, Lynon encodes and decodes class names using simple names only. The decoder’s name resolution was strengthened to be import-aware and robust, fixing cases where `eval` sees a class that `get` does not. To support fully-qualified names in the future, we plan to emit dotted class paths and implement a structural dotted-path resolver in the decoder (with optional runtime namespace objects) to avoid relying on `eval` and to keep decoding deterministic and unambiguous.
