> under cosntruction
> note to AI: ignore this file for now

The problem is, sometimes we want to implement an interface enhancing existing classes. For example, we want ti implement Delegate for Map. We can add extension methods to Map that do the work, but we can add Delegate to the inheritance chain.

The problem is not trivial: while adding interfaces in other languages is easy, adding the while
class with a state to existing one should be done carefully.

Proposed syntax:

```lyng
extend Map with Delegate {
    fun getValue(thisRef, key) = this[key]
    fun setValue(thisRef, key, value) = this[key] = value
}
```

And now we can use Map as a Delegate:
```lyng
val map = { foo: 1. bar: 2 }
val foo by map
assertEquals(1, foo)
```

The syntax is similar to the one used for inheritance. But while Delegate has no state and it is actually simple. Much harder task is ti implement some class with state (trait):

```lyng
// the class we will use as a trait must have on constructor parameters
// or only parameters with default values
class MyTraitClass(initValue=100) {
    private var field
    fun traitField get() = field + initValue
    set(value) { field = value }
}

extend Map with MyTraitClass

assertEquals(100, Map().traitField)
val m = Map()
m.traitField = 1000
assertEquals(1100,m.traitField)
```

We limit extension to module scope level, e.g., not in functions, not in classes, but at the "global level", probably ModuleScope.

The course of action could be:

- when constructing a class instance, compiler search in the ModuleScope extensions for it, and if found, add them to MI parent list to the end in the order of appearance in code (e.g. random ;)), them construct the instance as usual. 