package net.sergeych.lyng.obj

import net.sergeych.lyng.Arguments
import net.sergeych.lyng.Scope
import net.sergeych.lynon.LynonEncoder

class ObjInstance(override val objClass: ObjClass) : Obj() {

    internal lateinit var instanceScope: Scope

    override suspend fun readField(scope: Scope, name: String): ObjRecord {
        return instanceScope[name]?.let {
            if (it.visibility.isPublic)
                it
            else
                scope.raiseError(ObjAccessException(scope, "can't access non-public field $name"))
        }
            ?: super.readField(scope, name)
    }

    override suspend fun writeField(scope: Scope, name: String, newValue: Obj) {
        instanceScope[name]?.let { f ->
            if (!f.visibility.isPublic)
                ObjIllegalAssignmentException(scope, "can't assign to non-public field $name")
            if (!f.isMutable) ObjIllegalAssignmentException(scope, "can't reassign val $name").raise()
            if (f.value.assign(scope, newValue) == null)
                f.value = newValue
        } ?: super.writeField(scope, name, newValue)
    }

    override suspend fun invokeInstanceMethod(scope: Scope, name: String, args: Arguments): Obj =
        instanceScope[name]?.let {
            if (it.visibility.isPublic)
                it.value.invoke(scope, this, args)
            else
                scope.raiseError(ObjAccessException(scope, "can't invoke non-public method $name"))
        }
            ?: super.invokeInstanceMethod(scope, name, args)

    private val publicFields: Map<String, ObjRecord>
        get() = instanceScope.objects.filter { it.value.visibility.isPublic }

    override fun toString(): String {
        val fields = publicFields.map { "${it.key}=${it.value.value}" }.joinToString(",")
        return "${objClass.className}($fields)"
    }

    override suspend fun serialize(scope: Scope, encoder: LynonEncoder) {
        val meta = objClass.constructorMeta
            ?: scope.raiseError("can't serialize non-serializable object (no constructor meta)")
        for( p in meta.params) {
            val r = readField(scope, p.name)
            println("serialize ${p.name}=${r.value}")
            TODO()
//            encoder.encodeObj(scope, r.value)
        }
        // todo: possible vars?
    }

    override suspend fun compareTo(scope: Scope, other: Obj): Int {
        if (other !is ObjInstance) return -1
        if (other.objClass != objClass) return -1
        for (f in publicFields) {
            val a = f.value.value
            val b = other.instanceScope[f.key]!!.value
            val d = a.compareTo(scope, b)
            if (d != 0) return d
        }
        return 0
    }
}