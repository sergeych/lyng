@file:Suppress("unused")

package net.sergeych.lyng

/**
 * Iterator wrapper to allow Kotlin collections to be returned from Lyng objects;
 * each object is converted to a Lyng object.
 */
class ObjKotlinIterator(val iterator: Iterator<Any?>) : Obj() {

    override val objClass = type

    companion object {
        val type = ObjClass("KotlinIterator", ObjIterator).apply {
            addFn("next") { thisAs<ObjKotlinIterator>().iterator.next().toObj() }
            addFn("hasNext") { thisAs<ObjKotlinIterator>().iterator.hasNext().toObj() }
        }

    }
}

/**
 * Propagate kotlin iterator that already produces Lyng objects, no conversion
 * is applied
 */
class ObjKotlinObjIterator(val iterator: Iterator<Obj>) : Obj() {

    override val objClass = type

    companion object {
        val type = ObjClass("KotlinIterator", ObjIterator).apply {
            addFn("next") {
                thisAs<ObjKotlinObjIterator>().iterator.next()
            }
            addFn("hasNext") { thisAs<ObjKotlinIterator>().iterator.hasNext().toObj() }
        }

    }
}