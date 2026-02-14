/*
 * Kotlin bridge bindings for Lyng classes (Lyng-first workflow).
 */

package net.sergeych.lyng.bridge

import net.sergeych.lyng.Arguments
import net.sergeych.lyng.Pos
import net.sergeych.lyng.ScopeFacade
import net.sergeych.lyng.Script
import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjClass
import net.sergeych.lyng.obj.ObjExternCallable
import net.sergeych.lyng.obj.ObjInstance
import net.sergeych.lyng.obj.ObjProperty
import net.sergeych.lyng.obj.ObjRecord
import net.sergeych.lyng.obj.ObjVoid
import net.sergeych.lyng.pacman.ImportManager
import net.sergeych.lyng.ModuleScope
import net.sergeych.lyng.ScriptError
import net.sergeych.lyng.requiredArg
import net.sergeych.lyng.InstanceFieldInitStatement
import net.sergeych.lyng.Statement
import net.sergeych.lyng.bytecode.BytecodeStatement

interface BridgeInstanceContext {
    val instance: Obj
    var data: Any?
}

interface ClassBridgeBinder {
    var classData: Any?
    fun init(block: suspend BridgeInstanceContext.(ScopeFacade) -> Unit)
    fun initWithInstance(block: suspend (ScopeFacade, Obj) -> Unit)
    fun addFun(name: String, impl: suspend (ScopeFacade, Obj, Arguments) -> Obj)
    fun addVal(name: String, impl: suspend (ScopeFacade, Obj) -> Obj)
    fun addVar(
        name: String,
        get: suspend (ScopeFacade, Obj) -> Obj,
        set: suspend (ScopeFacade, Obj, Obj) -> Unit
    )
}

object LyngClassBridge {
    suspend fun bind(
        className: String,
        module: String? = null,
        importManager: ImportManager = Script.defaultImportManager,
        block: ClassBridgeBinder.() -> Unit
    ): ObjClass {
        val cls = resolveClass(className, module, null, importManager)
        return bind(cls, block)
    }

    suspend fun bind(
        moduleScope: ModuleScope,
        className: String,
        block: ClassBridgeBinder.() -> Unit
    ): ObjClass {
        val cls = resolveClass(className, null, moduleScope, Script.defaultImportManager)
        return bind(cls, block)
    }

    fun bind(clazz: ObjClass, block: ClassBridgeBinder.() -> Unit): ObjClass {
        val binder = ClassBridgeBinderImpl(clazz)
        binder.block()
        binder.commit()
        return clazz
    }
}

var ObjInstance.data: Any?
    get() = kotlinInstanceData
    set(value) { kotlinInstanceData = value }

var ObjClass.classData: Any?
    get() = kotlinClassData
    set(value) { kotlinClassData = value }

private enum class MemberKind { Instance, Static }

private data class MemberTarget(
    val name: String,
    val record: ObjRecord,
    val kind: MemberKind,
    val mirrorClassScope: Boolean = false
)

private class BridgeInstanceContextImpl(
    override val instance: Obj
) : BridgeInstanceContext {
    private fun instanceObj(): ObjInstance =
        instance as? ObjInstance ?: error("Bridge instance is not an ObjInstance")

    override var data: Any?
        get() = instanceObj().kotlinInstanceData
        set(value) { instanceObj().kotlinInstanceData = value }
}

private class ClassBridgeBinderImpl(
    private val cls: ObjClass
) : ClassBridgeBinder {
    private val initHooks = mutableListOf<suspend (ScopeFacade, ObjInstance) -> Unit>()
    private var checkedTemplate = false

    override var classData: Any?
        get() = cls.kotlinClassData
        set(value) { cls.kotlinClassData = value }

    override fun init(block: suspend BridgeInstanceContext.(ScopeFacade) -> Unit) {
        initHooks.add { scope, inst ->
            val ctx = BridgeInstanceContextImpl(inst)
            ctx.block(scope)
        }
    }

    override fun initWithInstance(block: suspend (ScopeFacade, Obj) -> Unit) {
        initHooks.add { scope, inst ->
            block(scope, inst)
        }
    }

    override fun addFun(name: String, impl: suspend (ScopeFacade, Obj, Arguments) -> Obj) {
        ensureTemplateNotBuilt()
        val target = findMember(name)
        val callable = ObjExternCallable.fromBridge {
            impl(this, thisObj, args)
        }
        val methodId = cls.ensureMethodIdForBridge(name, target.record)
        val newRecord = target.record.copy(
            value = callable,
            type = ObjRecord.Type.Fun,
            methodId = methodId
        )
        replaceMember(target, newRecord)
    }

    override fun addVal(name: String, impl: suspend (ScopeFacade, Obj) -> Obj) {
        ensureTemplateNotBuilt()
        val target = findMember(name)
        if (target.record.isMutable) {
            throw ScriptError(Pos.builtIn, "extern val $name is mutable in class ${cls.className}")
        }
        val getter = ObjExternCallable.fromBridge {
            impl(this, thisObj)
        }
        val prop = ObjProperty(name, getter, null)
        val isFieldLike = target.record.type == ObjRecord.Type.Field ||
            target.record.type == ObjRecord.Type.ConstructorField
        val newRecord = if (isFieldLike) {
            removeFieldInitializersFor(name)
            target.record.copy(
                value = prop,
                type = target.record.type,
                fieldId = target.record.fieldId,
                methodId = target.record.methodId
            )
        } else {
            val methodId = cls.ensureMethodIdForBridge(name, target.record)
            target.record.copy(
                value = prop,
                type = ObjRecord.Type.Property,
                methodId = methodId,
                fieldId = null
            )
        }
        replaceMember(target, newRecord)
    }

    override fun addVar(
        name: String,
        get: suspend (ScopeFacade, Obj) -> Obj,
        set: suspend (ScopeFacade, Obj, Obj) -> Unit
    ) {
        ensureTemplateNotBuilt()
        val target = findMember(name)
        if (!target.record.isMutable) {
            throw ScriptError(Pos.builtIn, "extern var $name is readonly in class ${cls.className}")
        }
        val getter = ObjExternCallable.fromBridge {
            get(this, thisObj)
        }
        val setter = ObjExternCallable.fromBridge {
            val value = requiredArg<Obj>(0)
            set(this, thisObj, value)
            ObjVoid
        }
        val prop = ObjProperty(name, getter, setter)
        val isFieldLike = target.record.type == ObjRecord.Type.Field ||
            target.record.type == ObjRecord.Type.ConstructorField
        val newRecord = if (isFieldLike) {
            removeFieldInitializersFor(name)
            target.record.copy(
                value = prop,
                type = target.record.type,
                fieldId = target.record.fieldId,
                methodId = target.record.methodId
            )
        } else {
            val methodId = cls.ensureMethodIdForBridge(name, target.record)
            target.record.copy(
                value = prop,
                type = ObjRecord.Type.Property,
                methodId = methodId,
                fieldId = null
            )
        }
        replaceMember(target, newRecord)
    }

    fun commit() {
        if (initHooks.isNotEmpty()) {
            val target = cls.bridgeInitHooks ?: mutableListOf<suspend (ScopeFacade, ObjInstance) -> Unit>().also {
                cls.bridgeInitHooks = it
            }
            target.addAll(initHooks)
        }
    }

    private fun ensureTemplateNotBuilt() {
        if (!checkedTemplate) {
            if (cls.instanceTemplateBuilt) {
                throw ScriptError(
                    Pos.builtIn,
                    "bridge binding for ${cls.className} must happen before first instance is created"
                )
            }
            checkedTemplate = true
        }
    }

    private fun replaceMember(target: MemberTarget, newRecord: ObjRecord) {
        when (target.kind) {
            MemberKind.Instance -> {
                cls.replaceMemberForBridge(target.name, newRecord)
                if (target.mirrorClassScope && cls.classScope?.objects?.containsKey(target.name) == true) {
                    cls.replaceClassScopeMemberForBridge(target.name, newRecord)
                }
            }
            MemberKind.Static -> cls.replaceClassScopeMemberForBridge(target.name, newRecord)
        }
    }

    private fun findMember(name: String): MemberTarget {
        val inst = cls.members[name]
        val stat = cls.classScope?.objects?.get(name)
        if (inst != null) {
            return MemberTarget(name, inst, MemberKind.Instance, mirrorClassScope = stat != null)
        }
        if (stat != null) return MemberTarget(name, stat, MemberKind.Static)
        throw ScriptError(Pos.builtIn, "extern member $name not found in class ${cls.className}")
    }

    private fun removeFieldInitializersFor(name: String) {
        if (cls.instanceInitializers.isEmpty()) return
        val storageName = cls.mangledName(name)
        cls.instanceInitializers.removeAll { init ->
            val stmt = init as? Statement ?: return@removeAll false
            val original = (stmt as? BytecodeStatement)?.original ?: stmt
            original is InstanceFieldInitStatement && original.storageName == storageName
        }
    }
}

private suspend fun resolveClass(
    className: String,
    module: String?,
    moduleScope: ModuleScope?,
    importManager: ImportManager
): ObjClass {
    val scope = moduleScope ?: run {
        if (module == null) {
            throw ScriptError(Pos.builtIn, "module is required to resolve $className")
        }
        importManager.createModuleScope(Pos.builtIn, module)
    }
    val rec = scope.get(className)
    val direct = rec?.value as? ObjClass
    if (direct != null) return direct
    if (className.contains('.')) {
        val resolved = scope.resolveQualifiedIdentifier(className)
        val cls = resolved as? ObjClass
        if (cls != null) return cls
    }
    throw ScriptError(Pos.builtIn, "class $className not found in module ${scope.packageName}")
}
