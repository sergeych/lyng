# Bytecode call-site PIC + fallback gating

Changes
- Added method call PIC path in bytecode VM with new CALL_SLOT/CALL_VIRTUAL opcodes.
- Fixed FieldRef property/delegate resolution to avoid bypassing ObjRecord delegation.
- Prevent delegated ObjRecord mutation by returning a resolved copy.
- Restricted bytecode call compilation to args that are ExpressionStatement (no splat/named/tail-block), fallback otherwise.

Rationale
- Fixes JVM test regressions and avoids premature evaluation of Statement args.
- Keeps delegated/property semantics identical to interpreter.

Tests
- ./gradlew :lynglib:jvmTest
- ./gradlew :lynglib:allTests -x :lynglib:jvmTest
