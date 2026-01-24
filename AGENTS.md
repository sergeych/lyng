# AI Agent Notes

## Kotlin/Wasm generation guardrails
- Avoid creating suspend lambdas for compiler runtime statements. Prefer explicit `object : Statement()` with `override suspend fun execute(...)`.
- Do not use `statement { ... }` or other inline suspend lambdas in compiler hot paths (e.g., parsing/var declarations, initializer thunks).
- If you need a wrapper for delegated properties, check for `getValue` explicitly and return a concrete `Statement` object when missing; avoid `onNotFoundResult` lambdas.
- If wasmJs browser tests hang, first run `:lynglib:wasmJsNodeTest` and look for wasm compilation errors; hangs usually mean module instantiation failed.
- Do not increase test timeouts to mask wasm generation errors; fix the invalid IR instead.
