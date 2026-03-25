# Lyng as a Common Platform for Smart Contracts and Distributed Execution

## Executive Summary

This proposal describes Lyng as a shared execution layer for:

- smart contracts across different blockchain networks,
- distributed compute workflows,
- decentralized application logic.

The goal is simple: write business logic once in Lyng, then run it consistently across chains, trusted execution nodes, and off-chain workers.

## Why a Common Lyng Layer

A unified language and runtime reduces fragmentation between contract code, off-chain services, and automation scripts.

Key benefits:

- One language across environments: the same Lyng code can be reused in contracts, validators, and distributed tasks.
- Capability-safe by default: no filesystem, process, or host access unless explicitly exposed by the host platform.
- Async-first execution model: lightweight concurrent tasks are natural, and host runtimes can schedule them efficiently.
- Strong typing with inference: concise source code without sacrificing type safety.
- Deterministic execution options: instruction/gas counting can be enabled for metered environments.
- Broad platform support: JVM, JS/Wasm, and native targets enable consistent tooling and deployment.

## Where It Fits

Lyng is a strong fit for:

- contract logic that must also run in simulation or off-chain verification,
- distributed orchestration and rule-based automations,
- cross-network shared libraries (validation, accounting rules, policy checks),
- deterministic business logic that should be portable between operators.

## Proposed Architecture

1. **Primary runtime**: Lyng VM/JIT for high-throughput trusted execution.
2. **Metered runtime**: Lyng VM mode with instruction counting and gas limits for blockchain execution.
3. **Standard API surface**:
   - core Lyng standard library,
   - storage contract (`key/value/tags`) shared across network adapters.
4. **Developer sandbox**:
   - local deterministic sandbox for test/debug,
   - optional feature-rich sandbox profile for integration testing.

## Strong Parts of the Lyng Approach

- **Code portability**: contract logic and off-chain logic remain aligned.
- **Lower maintenance**: fewer language/runtime rewrites across components.
- **Faster onboarding**: modern, readable syntax and concise constructs.
- **Safer host integration**: explicit capability injection limits accidental exposure.
- **Operational flexibility**: same package can run in metered or non-metered mode.

## Example: Deterministic Ledger Transfer

```lyng
class Ledger(storage) {
    fun transfer(from: Int, to: Int, amount: Int) {
        require(amount > 0)

        val fromKey = "bal:%05d"(from)
        val toKey = "bal:%05d"(to)

        val fromBal = storage[fromKey] ?: 0
        require(fromBal >= amount)

        storage[fromKey] = fromBal - amount
        storage[toKey] = (storage[toKey] ?: 0) + amount
    }
}
```

The same logic can run:

- in a gas-metered contract runtime,
- in off-chain simulation,
- in distributed batch reconciliation.

## Example: Parallel Verification Workflow

```lyng
fun verifyBatch(items, verifier) {
    val jobs = []

    for (item in items) {
        jobs += launch { verifier(item) }
    }

    val results = []
    for (job in jobs) {
        results += await(job)
    }

    results
}
```

This model is useful for distributed validation, scoring, and pre-consensus checks.

## Feature Showcases

### 1. Runtime generics with bounds (non-erased behavior)

```lyng
fun square<T: Int | Real>(x: T) = x * x

fun describeValue<T: Int | Real>(x: T): String = when (T) {
    Int -> "Int value=%s"(x)
    Real -> "Real value=%s"(x)
    else -> "Numeric value=%s"(x)
}

fun sameType<T>(x: T, y: Object): Bool = y is T
```

This enables practical runtime type-aware logic in generic code without JVM-style erasure limitations.

### 2. Multiple inheritance with lifted enums

```lyng
class Payable(val accountId: Int) {
    fun channel() = "onchain"
}

class Audited {
    fun mark() = "audited"
}

class Payment(accountId: Int) : Payable(accountId), Audited() {
    enum Status* { Pending, Settled }
}

val p = Payment(42)
assertEquals("onchain", p.channel())
assertEquals("audited", p.mark())
assertEquals(Payment.Pending, Payment.Status.Pending)
```

This combines reusable behavior composition with concise domain modeling.

### 3. Unified delegation model (`val`, `var`, `fun`)

```lyng
val state = { "owner": "alice", "limit": 10 }

val owner by state
var limit by state

object RpcDelegate {
    fun invoke(thisRef, name, args...) = "%s:%d"(name, args.size)
}
fun remoteCall by RpcDelegate

limit = 25
assertEquals(25, state["limit"])
assertEquals("remoteCall:2", remoteCall("alice", 10))
```

Delegation helps build compact, extensible models with minimal boilerplate.

## Rollout Strategy

1. Define the minimal portable storage API (`get/put/find by tags`) as a stable contract.
2. Publish a compatibility profile for metered execution (determinism + gas accounting).
3. Provide chain adapters that map Lyng storage/API calls to each target network.
4. Ship a reference sandbox and conformance tests for runtime parity.
5. Build shared Lyng libraries for common contract and compute patterns.
6. Launch a Lyng package manager and registry (NPM/Maven-style) with first-class support for unified scripts:
   - package metadata for runtime profile (metered/off-chain), storage API version, and capability requirements,
   - deterministic dependency resolution and lockfiles,
   - signed package publishing and verification for supply-chain integrity.

## Existing Resources

- Lyng project repository: https://github.com/sergeych/lyng
- Lyng docs home: https://lynglang.com
- Lyng tutorial: https://lynglang.com/#/docs/tutorial.md
- Embedding Lyng: https://lynglang.com/#/docs/embedding.md

## Conclusion

Lyng can serve as a practical common platform for blockchain contracts and distributed execution by combining:

- portable language semantics,
- safe-by-default host interaction,
- metered deterministic execution when required,
- and reusable code across on-chain and off-chain environments.

This reduces duplication, improves consistency, and shortens delivery cycles for decentralized systems.
