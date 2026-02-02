AI State (for session restart)

Project: /home/sergeych/dev/ling_lib
Module focus: :lynglib

Current focus
- Enforce compile-time name/member resolution only; no runtime scope lookup or fallback.
- Bytecode uses memberId-based ops (CALL_MEMBER_SLOT/GET_MEMBER_SLOT/SET_MEMBER_SLOT).
- Fallbacks are forbidden and throw BytecodeFallbackException.

Key recent changes
- Added memberId storage and lookup on ObjClass/ObjInstance; compiler emits memberId ops.
- Removed GET_THIS_MEMBER/SET_THIS_MEMBER usage from bytecode.
- Added ObjIterable.iterator() abstract method to ensure methodId exists.
- Bytecode compiler now throws if receiver type is unknown for member calls.
- Added static receiver inference attempts (name/slot maps), but still failing for stdlib list usage.
- Compiler now wraps function bodies into BytecodeStatement when possible.
- VarDeclStatement now includes initializerObjClass (compile-time type hint).

Known failing test
- ScriptTest.testForInIterableUnknownTypeDisasm (jvmTest)
  Failure: BytecodeFallbackException "Member call requires compile-time receiver type: add"
  Location: stdlib root.lyng filter() -> "val result = []" then "result.add(item)"
  Current debug shows LocalSlotRef(result) slotClass/nameClass/initClass all null.

Likely cause
- Initializer type inference for "val result = []" in stdlib is not reaching BytecodeCompiler:
  - stdlib is generated at build time; initializer is wrapped in BytecodeStatement and losing ListLiteralRef info.
  - Need a stable compile-time type hint from the compiler or a way to preserve list literal info.

Potential fixes to pursue
1) Preserve ObjRef for var initializers (e.g., store initializer ref/type in VarDeclStatement).
2) When initializer is BytecodeStatement, use its CmdFunction to detect list/range literal usage.
3) Ensure stdlib compilation sets initializerObjClass for list literals.

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
