AI State (for session restart)

Project: /home/sergeych/dev/ling_lib
Module focus: :lynglib

Current focus
- Enforce compile-time name/member resolution only; no runtime scope lookup or fallback.
- Bytecode uses memberId-based ops (CALL_MEMBER_SLOT/GET_MEMBER_SLOT/SET_MEMBER_SLOT).
- Runtime lookup opcodes (CALL_VIRTUAL/GET_FIELD/SET_FIELD) and fallback callsites are removed.

Key recent changes
- Removed method callsite PICs and fallback opcodes; bytecode now relies on compile-time member ids only.
- Operator dispatch emits memberId calls when known; falls back to Obj opcodes for allowed built-ins without name lookup.
- Object members are allowed on unknown types; other members still require a statically known receiver type.
- Renamed BytecodeFallbackException to BytecodeCompileException.

Known failing tests
- None (jvmTest passing).

Files touched recently
- notes/type_system_spec.md (spec updated)
- AGENTS.md (type inference reminders)
- lynglib/src/commonMain/kotlin/net/sergeych/lyng/VarDeclStatement.kt
- lynglib/src/commonMain/kotlin/net/sergeych/lyng/Compiler.kt
- lynglib/src/commonMain/kotlin/net/sergeych/lyng/bytecode/BytecodeCompiler.kt
- lynglib/src/commonMain/kotlin/net/sergeych/lyng/obj/ObjIterable.kt
- various bytecode runtime/disassembler files (memberId ops)

Last test run
- ./gradlew :lynglib:jvmTest --tests ScriptTest.testForInIterableUnknownTypeDisasm

Spec decisions (notes/type_system_spec.md)
- Nullability: Kotlin-style, T non-null, T? nullable, !! asserts non-null.
- void is singleton of class Void (syntax sugar).
- Untyped params default to Object (non-null); syntax sugar: fun foo(x?) and class X(a,b?).
- Object member access requires explicit cast; remove inspect, use toInspectString().
