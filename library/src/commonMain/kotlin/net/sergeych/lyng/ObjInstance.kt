package net.sergeych.lyng

class ObjInstance(override val objClass: ObjClass) : Obj() {

    internal lateinit var instanceContext: Context

    override suspend fun readField(context: Context, name: String): ObjRecord {
        return instanceContext[name]?.let {
            if (it.visibility.isPublic)
                it
            else
                context.raiseError(ObjAccessError(context, "can't access non-public field $name"))
        }
            ?: super.readField(context, name)
    }

    override suspend fun writeField(context: Context, name: String, newValue: Obj) {
        instanceContext[name]?.let { f ->
            if (!f.visibility.isPublic)
                ObjIllegalAssignmentError(context, "can't assign to non-public field $name")
            if (!f.isMutable) ObjIllegalAssignmentError(context, "can't reassign val $name").raise()
            if (f.value.assign(context, newValue) == null)
                f.value = newValue
        } ?: super.writeField(context, name, newValue)
    }

    override suspend fun invokeInstanceMethod(context: Context, name: String, args: Arguments): Obj =
        instanceContext[name]?.let {
            if (it.visibility.isPublic)
                it.value.invoke(context, this, args)
            else
                context.raiseError(ObjAccessError(context, "can't invoke non-public method $name"))
        }
            ?: super.invokeInstanceMethod(context, name, args)

    private val publicFields: Map<String, ObjRecord>
        get() = instanceContext.objects.filter { it.value.visibility.isPublic }

    override fun toString(): String {
        val fields = publicFields.map { "${it.key}=${it.value.value}" }.joinToString(",")
        return "${objClass.className}($fields)"
    }

    override suspend fun compareTo(context: Context, other: Obj): Int {
        if (other !is ObjInstance) return -1
        if (other.objClass != objClass) return -1
        for (f in publicFields) {
            val a = f.value.value
            val b = other.instanceContext[f.key]!!.value
            val d = a.compareTo(context, b)
            if (d != 0) return d
        }
        return 0
    }
}