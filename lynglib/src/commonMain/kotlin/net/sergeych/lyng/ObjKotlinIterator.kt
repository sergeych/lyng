@file:Suppress("unused")

package net.sergeych.lyng

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

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

/**
 * Convert Lyng's Iterable to Kotlin's Flow.
 *
 * As Lyng is totally asynchronous, its iterator can't be trivially converted to Kotlin's synchronous iterator.
 * It is, though, trivially convertible to Kotlin's Flow.
 */
fun Obj.toFlow(context: Context): Flow<Obj> = flow {
    val iterator = invokeInstanceMethod(context, "iterator")
    val hasNext = iterator.getInstanceMethod(context, "hasNext")
    val next = iterator.getInstanceMethod(context, "next")
    while (hasNext.invoke(context, iterator).toBool()) {
        emit(next.invoke(context, iterator))
    }
}