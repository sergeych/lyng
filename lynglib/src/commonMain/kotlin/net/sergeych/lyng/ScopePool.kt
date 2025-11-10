package net.sergeych.lyng

import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjVoid

/**
 * Simple, portable scope frame pool. JVM-first optimization; for now it uses a small
 * global deque. It is only used when [PerfFlags.SCOPE_POOL] is true.
 *
 * NOTE: This implementation is not thread-safe. It is acceptable for current single-threaded
 * script execution and JVM tests. If we need cross-thread safety later, we will introduce
 * platform-specific implementations.
 */
object ScopePool {
    private const val MAX_POOL_SIZE = 64
    private val pool = ArrayDeque<Scope>(MAX_POOL_SIZE)

    fun borrow(parent: Scope, args: Arguments, pos: Pos, thisObj: Obj): Scope {
        val s = if (pool.isNotEmpty()) pool.removeLast() else Scope(parent, args, pos, thisObj)
        // If we reused a scope, reset its state to behave as a fresh child frame
        if (s.parent !== parent || s.args !== args || s.pos !== pos || s.thisObj !== thisObj) {
            s.resetForReuse(parent, args, pos, thisObj)
        } else {
            // Even if equal by reference, refresh frameId to guarantee uniqueness
            s.frameId = nextFrameId()
        }
        return s
    }

    fun release(scope: Scope) {
        // Scrub sensitive references to avoid accidental retention
        scope.resetForReuse(parent = null, args = Arguments.EMPTY, pos = Pos.builtIn, thisObj = ObjVoid)
        if (pool.size < MAX_POOL_SIZE) pool.addLast(scope)
    }
}
