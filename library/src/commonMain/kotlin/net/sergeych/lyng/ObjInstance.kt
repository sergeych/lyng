package net.sergeych.lyng

class ObjInstance(override val objClass: ObjClass) : Obj() {

    internal val publicFields = mutableSetOf<String>()
    internal val protectedFields = mutableSetOf<String>()

    internal lateinit var instanceContext: Context

    override suspend fun readField(context: Context, name: String): ObjRecord {
        return if( name in publicFields ) instanceContext[name]!!
        else super.readField(context, name)
    }

    override suspend fun writeField(context: Context, name: String, newValue: Obj) {
        if( name in publicFields ) {
            val f = instanceContext[name]!!
            if( !f.isMutable ) ObjIllegalAssignmentError(context, "can't reassign val $name").raise()
            if( f.value.assign(context, newValue) == null)
                f.value = newValue
        }
        else super.writeField(context, name, newValue)
    }

    override suspend fun invokeInstanceMethod(context: Context, name: String, args: Arguments): Obj {
        if( name in publicFields ) return instanceContext[name]!!.value.invoke(context, this, args)
        return super.invokeInstanceMethod(context, name, args)
    }

    override fun toString(): String {
        val fields = publicFields.map {
                instanceContext[it]?.value?.toString() ?: "??"
        }.joinToString(", ")
        return "${objClass.className}($fields)"
    }

    override suspend fun compareTo(context: Context, other: Obj): Int {
        if( other !is ObjInstance ) return -1
        if( other.objClass != objClass ) return -1
        for( f in publicFields ) {
            val a = instanceContext[f]!!.value
            val b = other.instanceContext[f]!!.value
            val d = a.compareTo(context, b)
            if (d != 0) return d
        }
        return 0
    }
}