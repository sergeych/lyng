package net.sergeych.lyng

import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjVoid

/**
 * Wasm/JS actual: simple global deque pool (single-threaded runtime model).
 */
actual object ScopePool {
    private const val MAX_POOL_SIZE = 64
    private val pool = ArrayDeque<Scope>(MAX_POOL_SIZE)

    actual fun borrow(parent: Scope, args: Arguments, pos: Pos, thisObj: Obj): Scope {
        val s = if (pool.isNotEmpty()) pool.removeLast() else Scope(parent, args, pos, thisObj)
        if (s.parent !== parent || s.args !== args || s.pos !== pos || s.thisObj !== thisObj) {
            s.resetForReuse(parent, args, pos, thisObj)
        } else {
            s.frameId = nextFrameId()
        }
        return s
    }

    actual fun release(scope: Scope) {
        scope.resetForReuse(parent = null, args = Arguments.EMPTY, pos = Pos.builtIn, thisObj = ObjVoid)
        if (pool.size < MAX_POOL_SIZE) pool.addLast(scope)
    }
}
