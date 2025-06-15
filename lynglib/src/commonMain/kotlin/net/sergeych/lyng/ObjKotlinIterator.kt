package net.sergeych.lyng

class ObjKotlinIterator(val iterator: Iterator<Any?>): Obj() {

    override val objClass = type

    companion object {
        val type = ObjClass("KotlinIterator", ObjIterator).apply {
            addFn("next") { thisAs<ObjKotlinIterator>().iterator.next().toObj() }
            addFn("hasNext") { thisAs<ObjKotlinIterator>().iterator.hasNext().toObj() }
        }

    }
}