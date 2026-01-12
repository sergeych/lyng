# Lyng Project Guidelines

This project uses the Lyng scripting language for multiplatform scripting.

## Coding in Lyng
When writing, refactoring, or analyzing Lyng code:
- **Reference**: Always use `LYNG_AI_SPEC.md` in the project root as the primary source of truth for syntax and idioms.
- **File Extensions**: Use `.lyng` for all script files.
- **Implicit Coroutines**: Remember that all Lyng functions are implicitly coroutines; do not look for `async/await`.
- **Everything is an Expression**: Leverage the fact that blocks, if-statements, and loops return values.
- **Maps vs Blocks**: Be careful: `{}` is a block/lambda, use `Map()` for an empty map.
