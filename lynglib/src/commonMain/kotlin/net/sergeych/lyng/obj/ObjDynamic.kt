package net.sergeych.lyng.obj

import net.sergeych.lyng.Arguments
import net.sergeych.lyng.Scope
import net.sergeych.lyng.Statement

class ObjDynamicContext(val delegate: ObjDynamic) : Obj() {
    override val objClass: ObjClass = type

    companion object {
        val type = ObjClass("DelegateContext").apply {
            addFn("get") {
                val d = thisAs<ObjDynamicContext>().delegate
                if (d.readCallback != null)
                    raiseIllegalState("get already defined")
                d.readCallback = requireOnlyArg()
                ObjVoid
            }

            addFn("set") {
                val d = thisAs<ObjDynamicContext>().delegate
                if (d.writeCallback != null)
                    raiseIllegalState("set already defined")
                d.writeCallback = requireOnlyArg()
                ObjVoid
            }

        }

    }
}

class ObjDynamic : Obj() {

    internal var readCallback: Statement? = null
    internal var writeCallback: Statement? = null

    override suspend fun readField(scope: Scope, name: String): ObjRecord {
        return readCallback?.execute(scope.copy(Arguments(ObjString(name))))?.let {
            if (writeCallback != null)
                it.asMutable
            else
                it.asReadonly
        }
            ?: super.readField(scope, name)
    }

    override suspend fun writeField(scope: Scope, name: String, newValue: Obj) {
        writeCallback?.execute(scope.copy(Arguments(ObjString(name), newValue)))
            ?: super.writeField(scope, name, newValue)
    }

    companion object {

        suspend fun create(scope: Scope, builder: Statement): ObjDynamic {
            val delegate = ObjDynamic()
            val context = ObjDynamicContext(delegate)
            builder.execute(scope.copy(newThisObj = context))
            return delegate
        }

        val type = object : ObjClass("Delegate") {}
    }

}