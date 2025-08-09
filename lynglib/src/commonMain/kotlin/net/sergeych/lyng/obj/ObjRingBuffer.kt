package net.sergeych.lyng.obj

import net.sergeych.lyng.Scope

class RingBuffer<T>(val maxSize: Int) : Iterable<T> {
    private val data = arrayOfNulls<Any>(maxSize)

    var size = 0
        private set

    private var start = 0

    init {
        check(maxSize > 0) { "Max size should be a positive number: $maxSize" }
    }

    fun add(item: T) {
        if (size < maxSize)
            size++
        else
            start = (start + 1) % maxSize
        data[(start + size - 1) % maxSize] = item
    }

    @Suppress("unused")
    fun addAll(vararg items: T) {
        for (i in items) add(i)
    }

    @Suppress("unused")
    fun addAll(elements: Iterable<T>) {
        elements.forEach { add(it) }
    }

    @Suppress("unused")
    fun clear() {
        start = 0
        size = 0
        for (i in data.indices) {
            data[i] = null
        }
    }

    override fun iterator(): Iterator<T> =
        object : Iterator<T> {
            private var i = 0

            override fun hasNext(): Boolean = i < size

            override fun next(): T {
                if (!hasNext()) throw NoSuchElementException()

                @Suppress("UNCHECKED_CAST")
                return data[(start + i++) % maxSize] as T
            }
        }
}


class ObjRingBuffer(val capacity: Int) : Obj() {
    val buffer = RingBuffer<Obj>(capacity)

    override val objClass: ObjClass = type

    override suspend fun plusAssign(scope: Scope, other: Obj): Obj {
        buffer.add(other.byValueCopy())
        return this
    }

    companion object {
        val type = object : ObjClass("RingBuffer", ObjIterable) {
            override suspend fun callOn(scope: Scope): Obj {
                return ObjRingBuffer(scope.requireOnlyArg<ObjInt>().toInt())
            }
        }.apply {
            addFn("capacity") {
                thisAs<ObjRingBuffer>().capacity.toObj()
            }
            addFn("size") {
                thisAs<ObjRingBuffer>().buffer.size.toObj()
            }
            addFn("iterator") {
                val buffer = thisAs<ObjRingBuffer>().buffer
                ObjKotlinObjIterator(buffer.iterator())
            }
            addFn("add") {
                thisAs<ObjRingBuffer>().apply { buffer.add(requireOnlyArg<Obj>()) }
            }
        }
    }
}