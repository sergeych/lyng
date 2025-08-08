package net.sergeych.lyng

import net.sergeych.lyng.obj.ObjRecord

/**
 * Scope that adds a "closure" to caller; most often it is used to apply class instance to caller scope.
 * Inherits [Scope.args] and [Scope.thisObj] from [callScope] and adds lookup for symbols
 * from [closureScope] with proper precedence
 */
class ClosureScope(val callScope: Scope,val closureScope: Scope) : Scope(callScope, callScope.args, thisObj = callScope.thisObj) {

    override fun get(name: String): ObjRecord? {
        // closure should be treated below callScope
        return super.get(name) ?: closureScope.get(name)
    }
}