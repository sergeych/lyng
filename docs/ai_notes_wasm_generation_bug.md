# AI notes: avoid Kotlin/Wasm invalid IR with suspend lambdas

## Do
- Prefer explicit `object : Statement()` with `override suspend fun execute(...)` when building compiler statements.
- Keep `Statement` objects non-lambda, especially in compiler hot paths like parsing/var declarations.
- If you need conditional behavior, return early in `execute` instead of wrapping `parseExpression()` with `statement(...) { ... }`.
- When wasmJs tests hang in the browser, first check `wasmJsNodeTest` for a compile error; hangs often mean module instantiation failed.

## Don't
- Do not create suspend lambdas inside `Statement` factories (`statement { ... }`) for wasm targets.
- Do not "fix" hangs by increasing browser timeouts; it masks invalid wasm generation.

## Debugging tips
- Look for `$invokeCOROUTINE$` in wasm function names when mapping failures.
- If node test logs a wasm compile error, the browser hang is likely the same root cause.
