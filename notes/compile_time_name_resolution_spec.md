# Compile-Time Name Resolution Spec (Draft)

## Goals
- Resolve all identifiers at compile time; unresolved names are errors.
- Generate direct slot/method accesses with no runtime scope traversal.
- Make closure capture deterministic and safe (no accidental shadowing).
- Keep metaprogramming via explicit reflective APIs only.

## Non-Goals (initial phase)
- firbidden: Dynamic by-name lookup as part of core execution path.

- forbidden: Runtime scope walking to discover names.

## Overview
Compilation is split into two passes:
1) Declaration collection: gather all symbol definitions for each lexical scope.
2) Resolution/codegen: resolve every identifier to a concrete reference:
   - local/arg slot
   - captured slot (outer scope or module)
   - this-member slot or method slot
   - explicit reflection (opt-in)

If resolution fails, compilation errors immediately.

## Resolution Priority
When resolving a name `x` in a given scope:
1) Local variables in the current lexical scope (including shadowing).
2) Function parameters in the current lexical scope.
3) Local variables/parameters from outer lexical scopes (captures).
4) `this` members (fields/properties/functions), using MI linearization.
5) Module/global symbols (treated as deep captures).

Notes:
- Steps 3-5 require explicit capture or member-slot resolution.
- This order is deterministic and does not change at runtime.

## Closures and Captures
Closures capture a fixed list of referenced symbols from outer scopes.
- Captures are immutable references to outer slots unless the original is mutable.
- Captures are stored in the frame metadata, not looked up by name.
- Globals are just captures from the module frame.

Example:
```lyng
var g = 1
fun f(a) {
    var b = a + g
    return { b + g }
}
```
Compiled captures: `b` and `g`, both resolved at compile time.

## Capture Sources (Metadata)
Captures are a single mechanism. The module scope is simply the outermost
scope and is captured the same way as any other scope.

For debugging/tooling, captures are tagged with their origin:
- `local`: current lexical scope
- `outer`: enclosing lexical scope
- `module`: module/root scope

This tagging is metadata only and does not change runtime behavior.

## `this` Member Resolution (MI)
- Resolve members via MI linearization at compile time.
- Ambiguous or inaccessible members are compile-time errors.
- `override` is required when MI introduces conflicts.
- Qualified access is allowed when unambiguous:
  `this@BaseA.method()`

Example (conflict):
```lyng
class A { fun foo() = 1 }
class B { fun foo() = 2 }
class C : A, B { }  // error: requires override
```

## Shadowing Rules
Shadowing policy is configurable:
- Locals may shadow parameters (allowed by default).
- Locals may shadow captures/globals (allowed by default).
- Locals shadowing `this` members should emit warnings by default.
- Shadowing can be escalated to errors by policy.

Example (allowed by default):
```lyng
fun test(a) {
    var a = a * 10
    a
}
```

Suggested configuration (default):
- `shadow_param`: allow, warn = false
- `shadow_capture`: allow, warn = false
- `shadow_global`: allow, warn = false
- `shadow_member`: allow, warn = true

## Reflection and Metaprogramming
Reflection must be explicit:
- `scope.get("x")`/`scope.set("x", v)` are allowed but limited to the
  compile-time-visible set.
- No implicit name lookup falls back to reflection.
- Reflection uses frame metadata, not dynamic scope traversal.

Implication:
- Metaprogramming can still inspect locals/captures/members that were
  visible at compile time.
- Unknown names remain errors unless accessed explicitly via reflection.

### Reflection API (Lyng)
Proposed minimal surface:
- `scope.get(name: String): Obj?`  // only compile-time-visible names
- `scope.set(name: String, value: Obj)` // only if mutable and visible
- `scope.locals(): List<String>`  // visible locals in current frame
- `scope.captures(): List<String>` // visible captures for this frame
- `scope.members(): List<String>` // visible this-members for this frame

### Reflection API (Kotlin)
Expose a restricted view aligned with compile-time metadata:
- `Scope.getVisible(name: String): ObjRecord?`
- `Scope.setVisible(name: String, value: Obj)`
- `Scope.visibleLocals(): List<String>`
- `Scope.visibleCaptures(): List<String>`
- `Scope.visibleMembers(): List<String>`

Notes:
- These APIs never traverse parent scopes.
- Errors are thrown if name is not visible or not mutable.

## Frame Model
Each compiled unit includes:
- `localSlots`: fixed indexes for locals/args.
- `captureSlots`: fixed indexes for captured outer values.
- `thisSlots`: fixed member/method slots resolved at compile time.
- `debugNames`: optional for disassembly/debugger.

Slot resolution is constant-time with no name lookup in hot paths.

### Module Slot Allocation
Module slots are assigned deterministically per module:
- Stable order: declaration order in source (after preprocessing/import resolution).
- No reordering across builds unless source changes.
- Slots are fixed at compile time and embedded in compiled units.

Recommended metadata:
- `moduleName`
- `moduleSlotCount`
- `moduleSlotNames[]`
- `moduleSlotMutables[]`

### Capture Slot Allocation
Capture slots are assigned per compiled unit:
- Stable order: first occurrence in lexical traversal.
- Captures include locals, outer locals, and module symbols.
- Captures include mutability metadata and origin (local/outer/module).

Example capture table:
```
idx  name   origin     mutable
0    b      outer      true
1    G      module     false
```

## Error Cases (compile time)
- Unresolved identifier.
- Ambiguous MI member.
- Inaccessible member (visibility).
- Illegal write to immutable slot.

## Resolution Algorithm (pseudocode)
```
pass1_collect_decls(module):
  for each scope in module:
    record locals/args declared in that scope
  record module-level decls

pass2_resolve(module):
  for each compiled unit (function/block):
    for each identifier reference:
      if name in current_scope.locals:
        bind LocalSlot(current_scope, slot)
      else if name in current_scope.args:
        bind LocalSlot(current_scope, slot)
      else if name in any outer_scope.locals_or_args:
        bind CaptureSlot(outer_scope, slot)
      else if name in this_members:
        resolve via MI linearization
        bind ThisSlot(member_slot)
      else if name in module_symbols:
        bind CaptureSlot(module_scope, slot)
      else:
        error "unresolved name"

    for each assignment:
      verify target is mutable
      error if immutable
```

## Examples

### Local vs Member Shadowing
```lyng
class C { val x = 1 }
fun f() {
    val x = 2      // warning by default: shadows member
    x
}
```

### Closure Capture Determinism
```lyng
var g = 1
fun f() {
    var g = 2
    return { g }   // captures local g, not global
}
```

### Explicit Reflection
```lyng
fun f() {
    val x = 1
    scope.get("x") // ok (compile-time-visible set)
    scope.get("y") // error at compile time unless via explicit dynamic API
}
```

## Dry Run / Metadata Mode
The compiler supports a "dry run" that performs full declaration and
resolution without generating executable code. It returns:
- Symbol tables (locals, captures, members) with slots and origins
- Documentation strings and source positions
- MI linearization and member resolution results
- Shadowing diagnostics (warnings/errors)

This metadata drives:
- IDE autocompletion and navigation
- Mini-doc tooltips and documentation generators
- Static analysis (visibility and override checks)

## Migration Notes
- Keep reflection APIs separate to audit usage.
- Add warnings for member shadowing to surface risky code.

## Compatibility Notes (Kotlin interop)
- Provide minimal Kotlin-facing APIs that mirror compile-time-visible names.
- Do not preserve legacy runtime scope traversal.
- Any existing Kotlin code relying on dynamic lookup must migrate to
  explicit reflection calls or pre-resolved handles.
