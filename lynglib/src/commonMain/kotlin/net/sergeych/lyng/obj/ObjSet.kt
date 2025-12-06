/*
 * Copyright 2025 Sergey S. Chernov real.sergeych@gmail.com
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
import net.sergeych.lyng.miniast.TypeGenericDoc
import net.sergeych.lyng.miniast.addFnDoc
import net.sergeych.lyng.miniast.type
import net.sergeych.lynon.LynonDecoder
import net.sergeych.lynon.LynonEncoder
import net.sergeych.lynon.LynonType

class ObjSet(val set: MutableSet<Obj> = mutableSetOf()) : Obj() {

    override val objClass = type

    override suspend fun contains(scope: Scope, other: Obj): Boolean {
        return set.contains(other)
    }

    override suspend fun plus(scope: Scope, other: Obj): Obj {
        return ObjSet(
            if (other is ObjSet)
                (set + other.set).toMutableSet()
            else {
                if( other.isInstanceOf(ObjIterable) ) {
                    val otherSet = mutableSetOf<Obj>()
                    other.enumerate(scope) {
                        otherSet += it
                        true
                    }
                    (set + otherSet).toMutableSet()
                }
                else {
                    (set + other).toMutableSet()
                }
            }
        )
    }

    override suspend fun plusAssign(scope: Scope, other: Obj): Obj {
        when (other) {
            is ObjSet -> {
                set += other.set
            }

            is ObjList -> {
                set += other.list
            }

            else -> {
                if (other.isInstanceOf(ObjIterable)) {
                    val otherSet = mutableSetOf<Obj>()
                    other.enumerate(scope) {
                        otherSet += it
                        true
                    }
                    set += otherSet
                }
                else set += other
            }
        }
        return this
    }

    override suspend fun mul(scope: Scope, other: Obj): Obj {
        return if (other is ObjSet) {
            ObjSet(set.intersect(other.set).toMutableSet())
        } else
            scope.raiseIllegalArgument("set operator * requires another set")
    }

    override suspend fun minus(scope: Scope, other: Obj): Obj {
        return when {
            other is ObjSet -> ObjSet(set.minus(other.set).toMutableSet())
            other.isInstanceOf(ObjIterable) -> {
                val otherSet = mutableSetOf<Obj>()
                other.enumerate(scope) {
                    otherSet += it
                    true
                }
                ObjSet((set - otherSet).toMutableSet())
            }
            else ->
                scope.raiseIllegalArgument("set operator - requires another set or Iterable")
        }
    }

    override fun toString(): String {
        return "Set(${set.joinToString(", ")})"
    }

    override suspend fun compareTo(scope: Scope, other: Obj): Int {
        return if (other !is ObjSet) -1
        else {
            if (set == other.set) 0
            else -1
        }
    }

    override fun hashCode(): Int {
        return set.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as ObjSet

        return set == other.set
    }

    override suspend fun lynonType(): LynonType = LynonType.Set

    override suspend fun serialize(scope: Scope, encoder: LynonEncoder, lynonType: LynonType?) {
        encoder.encodeAnyList(scope, set.toList())
    }

    companion object {


        val type: ObjClass = object : ObjClass("Set", ObjCollection) {
            override suspend fun callOn(scope: Scope): Obj {
                return ObjSet(scope.args.list.toMutableSet())
            }

            override suspend fun deserialize(scope: Scope, decoder: LynonDecoder, lynonType: LynonType?): Obj =
                ObjSet(decoder.decodeAnyList(scope).toMutableSet())
        }.apply {
            addFnDoc(
                name = "size",
                doc = "Number of elements in this set.",
                returns = type("lyng.Int"),
                moduleName = "lyng.stdlib"
            ) {
                thisAs<ObjSet>().set.size.toObj()
            }
            addFnDoc(
                name = "intersect",
                doc = "Intersection with another set. Returns a new set.",
                params = listOf(ParamDoc("other", type("lyng.Set"))),
                returns = type("lyng.Set"),
                moduleName = "lyng.stdlib"
            ) {
                thisAs<ObjSet>().mul(this, args.firstAndOnly())
            }
            addFnDoc(
                name = "iterator",
                doc = "Iterator over elements of this set.",
                returns = TypeGenericDoc(type("lyng.Iterator"), listOf(type("lyng.Any"))),
                moduleName = "lyng.stdlib"
            ) {
                thisAs<ObjSet>().set.iterator().toObj()
            }
            addFnDoc(
                name = "union",
                doc = "Union with another set or iterable. Returns a new set.",
                params = listOf(ParamDoc("other")),
                returns = type("lyng.Set"),
                moduleName = "lyng.stdlib"
            ) {
                thisAs<ObjSet>().plus(this, args.firstAndOnly())
            }
            addFnDoc(
                name = "subtract",
                doc = "Subtract another set or iterable from this set. Returns a new set.",
                params = listOf(ParamDoc("other")),
                returns = type("lyng.Set"),
                moduleName = "lyng.stdlib"
            ) {
                thisAs<ObjSet>().minus(this, args.firstAndOnly())
            }
            addFnDoc(
                name = "remove",
                doc = "Remove one or more elements. Returns true if the set changed.",
                returns = type("lyng.Bool"),
                moduleName = "lyng.stdlib"
            ) {
                val set = thisAs<ObjSet>().set
                val n = set.size
                for( x in args.list ) set -= x
                if( n == set.size ) ObjFalse else ObjTrue
            }
        }
    }
}