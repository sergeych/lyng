package net.sergeych.lyng.obj

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.sergeych.lyng.Scope
import net.sergeych.lyng.Statement

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