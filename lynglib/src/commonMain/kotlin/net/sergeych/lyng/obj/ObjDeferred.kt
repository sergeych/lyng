package net.sergeych.lyng.obj

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.sergeych.lyng.Scope
import net.sergeych.lyng.Statement

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

class ObjMutex(val mutex: Mutex): Obj() {
    override val objClass = type

    companion object {
        val type = object: ObjClass("Mutex") {
            override suspend fun callOn(scope: Scope): Obj {
                return ObjMutex(Mutex())
            }
        }.apply {
            addFn("withLock") {
                val f = requiredArg<Statement>(0)
                thisAs<ObjMutex>().mutex.withLock { f.execute(this) }
            }
        }
    }
}