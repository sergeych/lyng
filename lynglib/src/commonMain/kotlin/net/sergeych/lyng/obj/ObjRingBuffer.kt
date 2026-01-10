/*
 * Copyright 2026 Sergey S. Chernov real.sergeych@gmail.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package net.sergeych.lyng.obj

import net.sergeych.lyng.Scope
import net.sergeych.lyng.miniast.*

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

    override val objClass: ObjClass get() = type

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
            addPropertyDoc(
                name = "capacity",
                doc = "Maximum number of elements the buffer can hold.",
                type = type("lyng.Int"),
                moduleName = "lyng.stdlib",
                getter = { thisAs<ObjRingBuffer>().capacity.toObj() }
            )
            addPropertyDoc(
                name = "size",
                doc = "Current number of elements in the buffer.",
                type = type("lyng.Int"),
                moduleName = "lyng.stdlib",
                getter = { thisAs<ObjRingBuffer>().buffer.size.toObj() }
            )
            addFnDoc(
                name = "iterator",
                doc = "Iterator over elements in insertion order (oldest to newest).",
                returns = TypeGenericDoc(type("lyng.Iterator"), listOf(type("lyng.Any"))),
                moduleName = "lyng.stdlib"
            ) {
                val buffer = thisAs<ObjRingBuffer>().buffer
                ObjKotlinObjIterator(buffer.iterator())
            }
            addFnDoc(
                name = "add",
                doc = "Append an element; if full, the oldest element is dropped.",
                params = listOf(ParamDoc("value", type("lyng.Any"))),
                returns = type("lyng.Void"),
                moduleName = "lyng.stdlib"
            ) { thisAs<ObjRingBuffer>().apply { buffer.add(requireOnlyArg<Obj>()) } }
            addPropertyDoc(
                name = "first",
                doc = "Return the oldest element in the buffer.",
                type = type("lyng.Any"),
                moduleName = "lyng.stdlib",
                getter = {
                    val buffer = (this.thisObj as ObjRingBuffer).buffer
                    if (buffer.size == 0) ObjNull else buffer.first()
                }
            )
        }
    }
}