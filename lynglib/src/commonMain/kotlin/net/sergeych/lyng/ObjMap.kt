package net.sergeych.lyng

class ObjMap(val map: MutableMap<Obj,Obj>): Obj() {

    override val objClass = type

    override suspend fun getAt(context: Context, index: Obj): Obj =
        map.getOrElse(index) { context.raiseNoSuchElement() }

    override suspend fun contains(context: Context, other: Obj): Boolean {
        return other in map
    }
    override fun toString(): String = map.toString()
    companion object {

        suspend fun listToMap(context: Context, list: List<Obj>): MutableMap<Obj,Obj> {
            val map = mutableMapOf<Obj, Obj>()
            if (list.isEmpty()) return map

            val first = list.first()
            if (first.isInstanceOf(ObjArray)) {
                if( first.invokeInstanceMethod(context,"size").toInt() != 2)
                    context.raiseIllegalArgument(
                        "list to construct map entry should exactly be 2 element Array like [key,value], got $list"
                    )
            } else context.raiseIllegalArgument("first element of map list be a Collection of 2 elements; got $first")



            list.forEach {
                map[it.getAt(context,ObjInt.Zero)] = it.getAt(context,ObjInt.One)
            }
            return map
        }


        val type = object: ObjClass("Map", ObjCollection) {
            override suspend fun callOn(context: Context): Obj {
                return ObjMap(listToMap(context, context.args.list))
            }
        }.apply {
            addFn("getOrNull") {
                val key = args.firstAndOnly(pos)
                thisAs<ObjMap>().map.getOrElse(key) { ObjNull }
            }
            addFn("getOrPut") {
                val key = requiredArg<Obj>(0)
                thisAs<ObjMap>().map.getOrPut(key) {
                    val lambda = requiredArg<Statement>(1)
                    lambda.execute(this)
                }
            }
            addFn("size") {
                thisAs<ObjMap>().map.size.toObj()
            }
            addFn("remove") {
                thisAs<ObjMap>().map.remove(requiredArg<Obj>(0))?.toObj() ?: ObjNull
            }
            addFn("clear") {
                thisAs<ObjMap>().map.clear()
                thisObj
            }
            addFn("keys") {
                thisAs<ObjMap>().map.keys.toObj()
            }
            addFn("values") {
                ObjList(thisAs<ObjMap>().map.values.toMutableList())
            }
        }
    }
}