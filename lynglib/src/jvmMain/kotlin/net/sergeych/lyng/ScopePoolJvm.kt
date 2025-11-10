package net.sergeych.lyng

import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjVoid

/**
 * JVM actual: per-thread scope frame pool backed by ThreadLocal.
 * Used only when [PerfFlags.SCOPE_POOL] is true.
 */
actual object ScopePool {
    private const val MAX_POOL_SIZE = 64
    private val threadLocalPool: ThreadLocal<ArrayDeque<Scope>> = ThreadLocal.withInitial {
        ArrayDeque<Scope>(MAX_POOL_SIZE)
    }

    actual fun borrow(parent: Scope, args: Arguments, pos: Pos, thisObj: Obj): Scope {
        val pool = threadLocalPool.get()
        val s = if (pool.isNotEmpty()) pool.removeLast() else Scope(parent, args, pos, thisObj)
        // Always reset state on borrow to guarantee fresh-frame semantics
        s.resetForReuse(parent, args, pos, thisObj)
        return s
    }

    actual fun release(scope: Scope) {
        val pool = threadLocalPool.get()
        // Scrub sensitive references to avoid accidental retention
        scope.resetForReuse(parent = null, args = Arguments.EMPTY, pos = Pos.builtIn, thisObj = ObjVoid)
        if (pool.size < MAX_POOL_SIZE) pool.addLast(scope)
    }
}
