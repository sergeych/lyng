# Kotlin Bridge: Lyng-First Class Binding

This note describes the Lyng-first workflow where a class is declared in Lyng and Kotlin provides extern implementations.

## Overview

- Lyng code declares a class and marks members as `extern`.
- Kotlin binds implementations with `LyngClassBridge.bind(...)`.
- Binding must happen **before the first instance is created**.
- `bind(className, module, importManager)` requires `module` to resolve class names; use
  `bind(moduleScope, className, ...)` or `bind(ObjClass, ...)` if you already have a scope/class.
- Kotlin can store two opaque payloads:
  - `instance.data` (per instance)
  - `classData` (per class)

## Lyng: declare extern members

```lyng
class Foo {
    extern fun add(a: Int, b: Int): Int
    extern val status: String
    extern var count: Int
    private extern fun secret(): Int
    static extern fun ping(): Int

    fun callAdd() = add(2, 3)
    fun callSecret() = secret()
    fun bump() { count = count + 1 }
}
```

## Kotlin: bind extern implementations

```kotlin
LyngClassBridge.bind(className = "Foo", module = "bridge.mod", importManager = im) {
    classData = "OK"

    init { _ ->
        data = CounterState(0)
    }

    addFun("add") {
        val a = (args.list[0] as ObjInt).value
        val b = (args.list[1] as ObjInt).value
        ObjInt.of(a + b)
    }

    addVal("status") { ObjString(classData as String) }

    addVar(
        "count",
        get = {
            val st = (thisObj as ObjInstance).data as CounterState
            ObjInt.of(st.count)
        },
        set = { value ->
            val st = (thisObj as ObjInstance).data as CounterState
            st.count = (value as ObjInt).value
        }
    )

    addFun("secret") { ObjInt.of(42) }
    addFun("ping") { ObjInt.of(7) }
}
```

## Notes

- Visibility is respected by usual Lyng access rules; private extern members can be used only within class code.
- Use `init { ... }` / `initWithInstance { ... }` with `ScopeFacade` receiver; access instance via `thisObj`.
- `classData` and `instance.data` are Kotlin-only payloads and do not appear in Lyng reflection.
- Binding after the first instance of a class is created throws a `ScriptError`.
