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
import net.sergeych.lyng.miniast.addFnDoc
import net.sergeych.lyng.miniast.addPropertyDoc
import net.sergeych.lyng.miniast.type
import net.sergeych.lynon.LynonDecoder
import net.sergeych.lynon.LynonEncoder
import net.sergeych.lynon.LynonType

class ObjImmutableList(items: List<Obj> = emptyList()) : Obj() {
    private val data: List<Obj> = items.toList()

    override val objClass: ObjClass
        get() = type

    override suspend fun equals(scope: Scope, other: Obj): Boolean {
        if (this === other) return true
        return when (other) {
            is ObjImmutableList -> data.size == other.data.size && data.indices.all { i -> data[i].equals(scope, other.data[i]) }
            else -> {
                if (other.isInstanceOf(ObjIterable)) compareTo(scope, other) == 0 else false
            }
        }
    }

    override suspend fun compareTo(scope: Scope, other: Obj): Int {
        if (other is ObjImmutableList) {
            val mySize = data.size
            val otherSize = other.data.size
            val commonSize = minOf(mySize, otherSize)
            for (i in 0..<commonSize) {
                val d = data[i].compareTo(scope, other.data[i])
                if (d != 0) return d
            }
            return mySize.compareTo(otherSize)
        }
        if (other.isInstanceOf(ObjIterable)) {
            val it1 = data.iterator()
            val it2 = other.invokeInstanceMethod(scope, "iterator")
            val hasNext2 = it2.getInstanceMethod(scope, "hasNext")
            val next2 = it2.getInstanceMethod(scope, "next")
            while (it1.hasNext()) {
                if (!hasNext2.invoke(scope, it2).toBool()) return 1
                val d = it1.next().compareTo(scope, next2.invoke(scope, it2))
                if (d != 0) return d
            }
            return if (hasNext2.invoke(scope, it2).toBool()) -1 else 0
        }
        return -2
    }

    override suspend fun getAt(scope: Scope, index: Obj): Obj {
        return when (index) {
            is ObjInt -> data[index.toInt()]
            is ObjRange -> {
                when {
                    index.start is ObjInt && index.end is ObjInt -> {
                        if (index.isEndInclusive)
                            ObjImmutableList(data.subList(index.start.toInt(), index.end.toInt() + 1))
                        else
                            ObjImmutableList(data.subList(index.start.toInt(), index.end.toInt()))
                    }
                    index.isOpenStart && !index.isOpenEnd -> {
                        if (index.isEndInclusive)
                            ObjImmutableList(data.subList(0, index.end!!.toInt() + 1))
                        else
                            ObjImmutableList(data.subList(0, index.end!!.toInt()))
                    }
                    index.isOpenEnd && !index.isOpenStart -> ObjImmutableList(data.subList(index.start!!.toInt(), data.size))
                    index.isOpenStart && index.isOpenEnd -> ObjImmutableList(data)
                    else -> throw RuntimeException("Can't apply range for index: $index")
                }
            }
            else -> scope.raiseIllegalArgument("Illegal index object for immutable list: ${index.inspect(scope)}")
        }
    }

    override suspend fun contains(scope: Scope, other: Obj): Boolean {
        if (net.sergeych.lyng.PerfFlags.PRIMITIVE_FASTOPS && other is ObjInt) {
            var i = 0
            val sz = data.size
            while (i < sz) {
                val v = data[i]
                if (v is ObjInt && v.value == other.value) return true
                i++
            }
            return false
        }
        return data.contains(other)
    }

    override suspend fun enumerate(scope: Scope, callback: suspend (Obj) -> Boolean) {
        for (item in data) {
            if (!callback(item)) break
        }
    }

    override suspend fun plus(scope: Scope, other: Obj): Obj {
        return when {
            other is ObjImmutableList -> ObjImmutableList(data + other.data)
            other is ObjList -> ObjImmutableList(data + other.list)
            other.isInstanceOf(ObjIterable) && other !is ObjString && other !is ObjBuffer -> {
                val l = other.callMethod<ObjList>(scope, "toList")
                ObjImmutableList(data + l.list)
            }
            else -> ObjImmutableList(data + other)
        }
    }

    override suspend fun minus(scope: Scope, other: Obj): Obj {
        if (other !is ObjString && other !is ObjBuffer && other.isInstanceOf(ObjIterable)) {
            val toRemove = mutableSetOf<Obj>()
            other.enumerate(scope) {
                toRemove += it
                true
            }
            return ObjImmutableList(data.filterNot { toRemove.contains(it) })
        }
        val out = data.toMutableList()
        out.remove(other)
        return ObjImmutableList(out)
    }

    override suspend fun serialize(scope: Scope, encoder: LynonEncoder, lynonType: LynonType?) {
        encoder.encodeAnyList(scope, data)
    }

    override suspend fun lynonType(): LynonType = LynonType.List

    override suspend fun defaultToString(scope: Scope): ObjString {
        return ObjString(buildString {
            append("ImmutableList(")
            var first = true
            for (v in data) {
                if (first) first = false else append(",")
                append(v.toString(scope).value)
            }
            append(")")
        })
    }

    fun toMutableList(): MutableList<Obj> = data.toMutableList()

    companion object {
        val type = object : ObjClass("ImmutableList", ObjArray) {
            override suspend fun callOn(scope: Scope): Obj {
                return ObjImmutableList(scope.args.list)
            }

            override suspend fun deserialize(scope: Scope, decoder: LynonDecoder, lynonType: LynonType?): Obj {
                return ObjImmutableList(decoder.decodeAnyList(scope))
            }
        }.apply {
            addPropertyDoc(
                name = "size",
                doc = "Number of elements in this immutable list.",
                type = type("lyng.Int"),
                moduleName = "lyng.stdlib",
                getter = { (this.thisObj as ObjImmutableList).data.size.toObj() }
            )
            addFnDoc(
                name = "toMutable",
                doc = "Create a mutable copy of this immutable list.",
                returns = type("lyng.List"),
                moduleName = "lyng.stdlib"
            ) {
                ObjList(thisAs<ObjImmutableList>().toMutableList())
            }
            addFnDoc(
                name = "toImmutable",
                doc = "Return this immutable list.",
                returns = type("lyng.ImmutableList"),
                moduleName = "lyng.stdlib"
            ) {
                thisObj
            }
        }
    }
}
