package net.sergeych.ling

val ObjClassType  by lazy { ObjClass("Class") }

class ObjClass(
    val className: String
): Obj() {

    override val objClass: ObjClass by lazy { ObjClassType }

    override fun toString(): String = className

    override suspend fun compareTo(context: Context, other: Obj): Int = if( other === this ) 0 else -1

//    val parents: List<ObjClass> get() = emptyList()

//    suspend fun callInstanceMethod(context: Context, name: String, self: Obj,args: Arguments): Obj {
//         getInstanceMethod(context, name).invoke(context, self,args)
//    }
}