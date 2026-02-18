# Scopes and Closures: compile-time resolution

Attention to AI: name lookup in runtime `Scope` is legacy. The bytecode compiler uses **compile-time name/member resolution only**.

This page documents the **current** rules: static name resolution, closure captures, and the limited role of runtime `Scope` in Kotlin interop and explicit dynamic helpers.

## Current rules (bytecode compiler)
- **All names resolve at compile time**: locals, parameters, captures, members, imports, and module globals must be known when compiling. Missing symbols are compile-time errors.
- **No runtime fallbacks**: there is no dynamic name lookup, no fallback opcodes, and no “search parent scopes” at runtime for missing names.
- **Object members on unknown types only**: `toString`, `toInspectString`, `let`, `also`, `apply`, `run` are allowed on unknown types; all other members require a statically known receiver type or an explicit cast.
- **Closures capture slots**: lambdas and nested functions capture **frame slots** directly. Captures are resolved at compile time and compiled to slot references.
- **Scope is a reflection facade**: `Scope` is used only for Kotlin interop or explicit dynamic helpers. It must **not** be used for general symbol resolution in compiled Lyng code.

## Explicit dynamic access (opt-in only)
Dynamic name access is available only via explicit helpers (e.g., `dynamic { get { name -> ... } }`). It is **not** a fallback for normal member or variable access.

## Legacy interpreter behavior (reference only)
The old runtime `Scope`-based resolution order (locals → captured → `this` → caller → globals) is obsolete for bytecode compilation. Keep it only for legacy interpreter paths and tooling that explicitly opts into it.
