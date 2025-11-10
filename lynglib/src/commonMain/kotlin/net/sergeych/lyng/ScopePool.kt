package net.sergeych.lyng

import net.sergeych.lyng.obj.Obj

/**
 * Expect/actual portable scope frame pool. Used only when [PerfFlags.SCOPE_POOL] is true.
 * JVM actual provides a ThreadLocal-backed pool; other targets may use a simple global deque.
 */
expect object ScopePool {
    fun borrow(parent: Scope, args: Arguments, pos: Pos, thisObj: Obj): Scope
    fun release(scope: Scope)
}
