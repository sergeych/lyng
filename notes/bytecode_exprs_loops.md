# Bytecode expression + for-in loop support

Changes
- Added bytecode compilation for conditional/elvis expressions, inc/dec, and compound assignments where safe.
- Added ForInStatement and ConstIntRange to keep for-loop structure explicit (no anonymous Statement).
- Added PUSH_SCOPE/POP_SCOPE opcodes with SlotPlan constants to create loop scopes in bytecode.
- Bytecode compiler emits int-range for-in loops when const range is known and no break/continue.

Tests
- ./gradlew :lynglib:jvmTest
- ./gradlew :lynglib:allTests -x :lynglib:jvmTest
