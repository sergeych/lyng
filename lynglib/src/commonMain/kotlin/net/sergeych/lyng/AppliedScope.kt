package net.sergeych.lyng

import net.sergeych.lyng.obj.ObjRecord

/**
 * Special version of the [Scope] used to `apply` new this object to
 * _parent context property.
 *
 * @param _parent context to apply to
 * @param args arguments for the new context
 * @param appliedScope the new context to apply, it will have lower priority except for `this` which
 *      will be reset by appliedContext's `this`.
 */
class AppliedScope(_parent: Scope, args: Arguments, val appliedScope: Scope)
    : Scope(_parent, args, appliedScope.pos, appliedScope.thisObj) {
    override fun get(name: String): ObjRecord? =
        if (name == "this") thisObj.asReadonly
        else super.get(name) ?: appliedScope[name]
}