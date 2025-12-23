package net.sergeych.lyng.obj

import net.sergeych.lyng.Arguments
import net.sergeych.lyng.Scope
import net.sergeych.lyng.Statement

/**
 * Property accessor storage. Per instructions, properties do NOT have
 * automatic backing fields. They are pure accessors.
 */
class ObjProperty(
    val name: String,
    val getter: Statement?,
    val setter: Statement?
) : Obj() {

    suspend fun callGetter(scope: Scope, instance: ObjInstance): Obj {
        val g = getter ?: scope.raiseError("property $name has no getter")
        // Execute getter in a child scope of the instance with 'this' properly set
        return g.execute(instance.instanceScope.createChildScope(newThisObj = instance))
    }

    suspend fun callSetter(scope: Scope, instance: ObjInstance, value: Obj) {
        val s = setter ?: scope.raiseError("property $name has no setter")
        // Execute setter in a child scope of the instance with 'this' properly set and the value as an argument
        s.execute(instance.instanceScope.createChildScope(args = Arguments(value), newThisObj = instance))
    }

    override fun toString(): String = "Property($name)"
}
