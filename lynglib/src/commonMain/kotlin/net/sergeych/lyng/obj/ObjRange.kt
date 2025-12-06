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
import net.sergeych.lyng.miniast.TypeGenericDoc
import net.sergeych.lyng.miniast.addFnDoc
import net.sergeych.lyng.miniast.type

class ObjRange(val start: Obj?, val end: Obj?, val isEndInclusive: Boolean) : Obj() {

    val isOpenStart by lazy { start == null || start.isNull }
    val isOpenEnd by lazy { end == null || end.isNull }

    override val objClass: ObjClass = type

    override suspend fun toString(scope: Scope,calledFromLyng: Boolean): ObjString {
        val result = StringBuilder()
        result.append("${start?.inspect(scope) ?: '∞'} ..")
        if (!isEndInclusive) result.append('<')
        result.append(" ${end?.inspect(scope) ?: '∞'}")
        return ObjString(result.toString())
    }

    /**
     * IF end is open (null/ObjNull), returns null
     * Otherwise, return correct value for the exclusive end
     * raises [ObjIllegalArgumentException] if end is not ObjInt
     */
    fun exclusiveIntEnd(scope: Scope): Int? =
        if (end == null || end is ObjNull) null
        else {
            if (end !is ObjInt) scope.raiseIllegalArgument("end is not int")
            if (isEndInclusive) end.value.toInt() + 1 else end.value.toInt()
        }


    /**
     * If start is null/ObjNull, returns 0
     * if start is not ObjInt, raises [ObjIllegalArgumentException]
     * otherwise returns start.value.toInt()
     */
    suspend fun startInt(scope: Scope): Int =
        if( start == null || start is ObjNull) 0
        else {
            if( start is ObjInt) start.value.toInt()
            else scope.raiseIllegalArgument("start is not Int: ${start.inspect(scope)}")
        }

    suspend fun containsRange(scope: Scope, other: ObjRange): Boolean {
        if (start != null) {
            // our start is not -∞ so other start should be GTE or is not contained:
            if (other.start != null && start.compareTo(scope, other.start) > 0) return false
        }
        if (end != null) {
            // same with the end: if it is open, it can't be contained in ours:
            if (other.end == null) return false
            // both exists, now there could be 4 cases:
            return when {
                other.isEndInclusive && isEndInclusive ->
                    end.compareTo(scope, other.end) >= 0

                !other.isEndInclusive && !isEndInclusive ->
                    end.compareTo(scope, other.end) >= 0

                other.isEndInclusive && !isEndInclusive ->
                    end.compareTo(scope, other.end) > 0

                !other.isEndInclusive && isEndInclusive ->
                    end.compareTo(scope, other.end) >= 0

                else -> throw IllegalStateException("unknown comparison")
            }
        }
        return true
    }

    override suspend fun contains(scope: Scope, other: Obj): Boolean {

        if (other is ObjRange)
            return containsRange(scope, other)

        if (start == null && end == null) return true
        if (start != null) {
            if (start.compareTo(scope, other) > 0) return false
        }
        if (end != null) {
            val cmp = end.compareTo(scope, other)
            if (isEndInclusive && cmp < 0 || !isEndInclusive && cmp <= 0) return false
        }
        return true
    }

    val isIntRange: Boolean by lazy {
        start is ObjInt && end is ObjInt
    }

    val isCharRange: Boolean by lazy {
        start is ObjChar && end is ObjChar
    }

    override suspend fun compareTo(scope: Scope, other: Obj): Int {
        return (other as? ObjRange)?.let {
            if( start == other.start && end == other.end ) 0 else -1
        }
            ?: -1
    }

    override fun hashCode(): Int {
        var result = start?.hashCode() ?: 0
        result = 31 * result + (end?.hashCode() ?: 0)
        result = 31 * result + isEndInclusive.hashCode()
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as ObjRange

        if (start != other.start) return false
        if (end != other.end) return false
        if (isEndInclusive != other.isEndInclusive) return false

        return true
    }


    companion object {
        val type = ObjClass("Range", ObjIterable).apply {
            addFnDoc(
                name = "start",
                doc = "Start bound of the range or null if open.",
                returns = type("lyng.Any", nullable = true),
                moduleName = "lyng.stdlib"
            ) {
                thisAs<ObjRange>().start ?: ObjNull
            }
            addFnDoc(
                name = "end",
                doc = "End bound of the range or null if open.",
                returns = type("lyng.Any", nullable = true),
                moduleName = "lyng.stdlib"
            ) {
                thisAs<ObjRange>().end ?: ObjNull
            }
            addFnDoc(
                name = "isOpen",
                doc = "Whether the range is open on either side (no start or no end).",
                returns = type("lyng.Bool"),
                moduleName = "lyng.stdlib"
            ) {
                thisAs<ObjRange>().let { it.start == null || it.end == null }.toObj()
            }
            addFnDoc(
                name = "isIntRange",
                doc = "True if both bounds are Int values.",
                returns = type("lyng.Bool"),
                moduleName = "lyng.stdlib"
            ) {
                thisAs<ObjRange>().isIntRange.toObj()
            }
            addFnDoc(
                name = "isCharRange",
                doc = "True if both bounds are Char values.",
                returns = type("lyng.Bool"),
                moduleName = "lyng.stdlib"
            ) {
                thisAs<ObjRange>().isCharRange.toObj()
            }
            addFnDoc(
                name = "isEndInclusive",
                doc = "Whether the end bound is inclusive.",
                returns = type("lyng.Bool"),
                moduleName = "lyng.stdlib"
            ) {
                thisAs<ObjRange>().isEndInclusive.toObj()
            }
            addFnDoc(
                name = "iterator",
                doc = "Iterator over elements in this range (optimized for Int ranges).",
                returns = TypeGenericDoc(type("lyng.Iterator"), listOf(type("lyng.Any"))),
                moduleName = "lyng.stdlib"
            ) {
                val self = thisAs<ObjRange>()
                if (net.sergeych.lyng.PerfFlags.RANGE_FAST_ITER) {
                    val s = self.start
                    val e = self.end
                    if (s is ObjInt && e is ObjInt) {
                        val start = s.value.toInt()
                        val endExclusive = (if (self.isEndInclusive) e.value.toInt() + 1 else e.value.toInt())
                        // Only for ascending simple ranges; fall back otherwise
                        if (start <= endExclusive) {
                            return@addFnDoc ObjFastIntRangeIterator(start, endExclusive)
                        }
                    }
                }
                ObjRangeIterator(self).apply { init() }
            }
        }
    }
}

