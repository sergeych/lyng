package net.sergeych.lyng

/**
 * Special version of the [Context] used to `apply` new this object to
 * _parent context property.
 *
 * @param _parent context to apply to
 * @param args arguments for the new context
 * @param appliedContext the new context to apply, it will have lower priority except for `this` which
 *      will be reset by appliedContext's `this`.
 */
class AppliedContext(_parent: Context, args: Arguments, val appliedContext: Context)
    : Context(_parent, args, appliedContext.pos, appliedContext.thisObj) {
    override fun get(name: String): ObjRecord? =
        if (name == "this") thisObj.asReadonly
        else super.get(name) ?: appliedContext[name]
}