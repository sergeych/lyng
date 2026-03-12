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
import net.sergeych.lyng.miniast.ParamDoc
import net.sergeych.lyng.miniast.addFnDoc
import net.sergeych.lyng.miniast.type
import net.sergeych.lynon.LynonDecoder
import net.sergeych.lynon.LynonEncoder
import net.sergeych.lynon.LynonType

class ObjImmutableSet(items: Collection<Obj> = emptyList()) : Obj() {
    private val data: Set<Obj> = LinkedHashSet(items)

    override val objClass get() = type

    override suspend fun equals(scope: Scope, other: Obj): Boolean {
        if (this === other) return true
        val otherSet = when (other) {
            is ObjImmutableSet -> other.data
            is ObjSet -> other.set
            else -> return false
        }
        if (data.size != otherSet.size) return false
        for (e in data) {
            if (!other.contains(scope, e)) return false
        }
        return true
    }

    override suspend fun compareTo(scope: Scope, other: Obj): Int {
        val otherSet = when (other) {
            is ObjImmutableSet -> other.data
            is ObjSet -> other.set
            else -> return -2
        }
        if (data == otherSet) return 0
        if (data.size != otherSet.size) return data.size.compareTo(otherSet.size)
        return data.toString().compareTo(otherSet.toString())
    }

    override suspend fun contains(scope: Scope, other: Obj): Boolean = data.contains(other)

    override suspend fun enumerate(scope: Scope, callback: suspend (Obj) -> Boolean) {
        for (item in data) {
            if (!callback(item)) break
        }
    }

    override suspend fun plus(scope: Scope, other: Obj): Obj {
        val merged = LinkedHashSet(data)
        when {
            other is ObjImmutableSet -> merged.addAll(other.data)
            other is ObjSet -> merged.addAll(other.set)
            other is ObjString || other is ObjBuffer || !other.isInstanceOf(ObjIterable) -> merged.add(other)
            else -> other.enumerate(scope) { merged += it; true }
        }
        return ObjImmutableSet(merged)
    }

    override suspend fun minus(scope: Scope, other: Obj): Obj {
        val out = LinkedHashSet(data)
        when {
            other is ObjImmutableSet -> out.removeAll(other.data)
            other is ObjSet -> out.removeAll(other.set)
            other is ObjString || other is ObjBuffer || !other.isInstanceOf(ObjIterable) -> out.remove(other)
            else -> other.enumerate(scope) { out.remove(it); true }
        }
        return ObjImmutableSet(out)
    }

    override suspend fun mul(scope: Scope, other: Obj): Obj {
        val right = when (other) {
            is ObjImmutableSet -> other.data
            is ObjSet -> other.set
            else -> scope.raiseIllegalArgument("set operator * requires another set")
        }
        return ObjImmutableSet(data.intersect(right))
    }

    override suspend fun lynonType(): LynonType = LynonType.Set

    override suspend fun serialize(scope: Scope, encoder: LynonEncoder, lynonType: LynonType?) {
        encoder.encodeAnyList(scope, data.toList())
    }

    fun toMutableSet(): MutableSet<Obj> = LinkedHashSet(data)

    companion object {
        val type: ObjClass = object : ObjClass("ImmutableSet", ObjCollection) {
            override suspend fun callOn(scope: Scope): Obj {
                return ObjImmutableSet(scope.args.list)
            }

            override suspend fun deserialize(scope: Scope, decoder: LynonDecoder, lynonType: LynonType?): Obj =
                ObjImmutableSet(decoder.decodeAnyList(scope))
        }.apply {
            addFnDoc(
                name = "size",
                doc = "Number of elements in this immutable set.",
                returns = type("lyng.Int"),
                moduleName = "lyng.stdlib"
            ) {
                thisAs<ObjImmutableSet>().data.size.toObj()
            }
            addFnDoc(
                name = "intersect",
                doc = "Intersection with another set. Returns a new immutable set.",
                params = listOf(ParamDoc("other")),
                returns = type("lyng.ImmutableSet"),
                moduleName = "lyng.stdlib"
            ) {
                thisAs<ObjImmutableSet>().mul(requireScope(), args.firstAndOnly())
            }
            addFnDoc(
                name = "iterator",
                doc = "Iterator over elements of this immutable set.",
                moduleName = "lyng.stdlib"
            ) {
                ObjKotlinObjIterator(thisAs<ObjImmutableSet>().data.iterator())
            }
            addFnDoc(
                name = "union",
                doc = "Union with another set or iterable. Returns a new immutable set.",
                params = listOf(ParamDoc("other")),
                returns = type("lyng.ImmutableSet"),
                moduleName = "lyng.stdlib"
            ) {
                thisAs<ObjImmutableSet>().plus(requireScope(), args.firstAndOnly())
            }
            addFnDoc(
                name = "subtract",
                doc = "Subtract another set or iterable from this set. Returns a new immutable set.",
                params = listOf(ParamDoc("other")),
                returns = type("lyng.ImmutableSet"),
                moduleName = "lyng.stdlib"
            ) {
                thisAs<ObjImmutableSet>().minus(requireScope(), args.firstAndOnly())
            }
            addFnDoc(
                name = "toMutable",
                doc = "Create a mutable copy of this immutable set.",
                returns = type("lyng.Set"),
                moduleName = "lyng.stdlib"
            ) {
                ObjSet(thisAs<ObjImmutableSet>().toMutableSet())
            }
            addFnDoc(
                name = "toImmutable",
                doc = "Return this immutable set.",
                returns = type("lyng.ImmutableSet"),
                moduleName = "lyng.stdlib"
            ) { thisObj }
        }
    }
}
