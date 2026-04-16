package net.sergeych.lyng.obj

import net.sergeych.lyng.ScopeFacade
import net.sergeych.lyng.requiredArg

internal fun ObjClass.bindClassFn(name: String, code: suspend ScopeFacade.() -> Obj) {
    val callable = ObjExternCallable.fromBridge { code() }
    val memberRecord = members[name]
    val classScopeRecord = classScope?.objects?.get(name)
    if (memberRecord != null) {
        val methodId = ensureMethodIdForBridge(name, memberRecord)
        val newRecord = memberRecord.copy(
            value = callable,
            type = ObjRecord.Type.Fun,
            methodId = methodId,
            isAbstract = false,
        )
        replaceMemberForBridge(name, newRecord)
        if (classScopeRecord != null) {
            replaceClassScopeMemberForBridge(name, newRecord)
        }
    } else if (classScopeRecord != null) {
        val methodId = ensureMethodIdForBridge(name, classScopeRecord)
        replaceClassScopeMemberForBridge(
            name,
            classScopeRecord.copy(
                value = callable,
                type = ObjRecord.Type.Fun,
                methodId = methodId,
                isAbstract = false,
            )
        )
    } else {
        addClassFn(name, code = code)
    }
}

internal fun ObjClass.bindProperty(
    name: String,
    getter: (suspend ScopeFacade.() -> Obj)? = null,
    setter: (suspend ScopeFacade.(Obj) -> Unit)? = null,
) {
    val g = getter?.let { ObjExternCallable.fromBridge { it() } }
    val s = setter?.let { ObjExternCallable.fromBridge { it(requiredArg(0)); ObjVoid } }
    val prop = ObjProperty(name, g, s)
    val existing = members[name]
    if (existing != null) {
        val newRecord = existing.copy(
            value = prop,
            type = ObjRecord.Type.Property,
            methodId = ensureMethodIdForBridge(name, existing),
            fieldId = null,
            isAbstract = false,
        )
        replaceMemberForBridge(name, newRecord)
        if (classScope?.objects?.containsKey(name) == true) {
            replaceClassScopeMemberForBridge(name, newRecord)
        }
    } else {
        addProperty(name, getter = getter, setter = setter)
    }
}
