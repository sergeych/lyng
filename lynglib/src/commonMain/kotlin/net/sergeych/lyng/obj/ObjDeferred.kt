package net.sergeych.lyng.obj

import kotlinx.coroutines.Deferred
import net.sergeych.lyng.Scope

open class ObjDeferred(val deferred: Deferred<Obj>): Obj() {

    override val objClass = type

    companion object {
        val type = object: ObjClass("Deferred"){
            override suspend fun callOn(scope: Scope): Obj {
                scope.raiseError("Deferred constructor is not directly callable")
            }
        }.apply {
            addFn("await") {
                thisAs<ObjDeferred>().deferred.await()
            }
            addFn("isCompleted") {
                thisAs<ObjDeferred>().deferred.isCompleted.toObj()
            }
            addFn("isActive") {
                thisAs<ObjDeferred>().deferred.isActive.toObj()
            }
            addFn("isCancelled") {
                thisAs<ObjDeferred>().deferred.isCancelled.toObj()
            }

        }
    }
}

