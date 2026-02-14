# Bytecode Frame-First Migration Plan (Completed)

Status: completed (archived for historical reference)

Original goals
- Migrate bytecode to frame-first locals with lazy scope reflection; avoid eager scope slot plans in compiled functions.
- Use FrameSlotRef for captures and only materialize Scope for Kotlin interop.
- Avoid PUSH_SCOPE/POP_SCOPE in bytecode for loops/functions unless dynamic name access or Kotlin reflection is requested.

Notes
- This plan is now considered done at the architectural level; see current work items in other notes.
- Scope slots are still present for interop in the current codebase; removal is tracked separately.
