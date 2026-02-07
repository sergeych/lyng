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
import net.sergeych.lyng.miniast.TypeGenericDoc
import net.sergeych.lyng.miniast.addFnDoc
import net.sergeych.lyng.miniast.addPropertyDoc
import net.sergeych.lyng.miniast.type

class ObjRange(
    val start: Obj?,
    val end: Obj?,
    val isEndInclusive: Boolean,
    val step: Obj? = null
) : Obj() {

    val isOpenStart by lazy { start == null || start.isNull }
    val isOpenEnd by lazy { end == null || end.isNull }
    val hasExplicitStep: Boolean get() = step != null && !step.isNull

    override val objClass: ObjClass get() = type

    override suspend fun defaultToString(scope: Scope): ObjString {
        val result = StringBuilder()
        result.append("${start?.inspect(scope) ?: '∞'} ..")
        if (!isEndInclusive) result.append('<')
        result.append(" ${end?.inspect(scope) ?: '∞'}")
        if (hasExplicitStep) {
            result.append(" step ${step?.inspect(scope)}")
        }
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
        if (!isOpenStart) {
            // our start is not -∞ so other start should be GTE or is not contained:
            if (!other.isOpenStart && start!!.compareTo(scope, other.start!!) > 0) return false
        }
        if (!isOpenEnd) {
            // same with the end: if it is open, it can't be contained in ours:
            if (other.isOpenEnd) return false
            // both exists, now there could be 4 cases:
            return when {
                other.isEndInclusive && isEndInclusive ->
                    end!!.compareTo(scope, other.end!!) >= 0

                !other.isEndInclusive && !isEndInclusive ->
                    end!!.compareTo(scope, other.end!!) >= 0

                other.isEndInclusive && !isEndInclusive ->
                    end!!.compareTo(scope, other.end!!) > 0

                !other.isEndInclusive && isEndInclusive ->
                    end!!.compareTo(scope, other.end!!) >= 0

                else -> throw IllegalStateException("unknown comparison")
            }
        }
        return true
    }

    override suspend fun contains(scope: Scope, other: Obj): Boolean {

        if (other is ObjRange)
            return containsRange(scope, other)

        if (net.sergeych.lyng.PerfFlags.PRIMITIVE_FASTOPS) {
            if (start is ObjInt && end is ObjInt && other is ObjInt) {
                val s = start.value
                val e = end.value
                val v = other.value
                if (v < s) return false
                return if (isEndInclusive) v <= e else v < e
            }
            if (start is ObjChar && end is ObjChar && other is ObjChar) {
                val s = start.value
                val e = end.value
                val v = other.value
                if (v < s) return false
                return if (isEndInclusive) v <= e else v < e
            }
            if (start is ObjString && end is ObjString && other is ObjString) {
                val s = start.value
                val e = end.value
                val v = other.value
                if (v < s) return false
                return if (isEndInclusive) v <= e else v < e
            }
        }

        if (isOpenStart && isOpenEnd) return true
        if (!isOpenStart) {
            if (start!!.compareTo(scope, other) > 0) return false
        }
        if (!isOpenEnd) {
            val cmp = end!!.compareTo(scope, other)
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

    override suspend fun enumerate(scope: Scope, callback: suspend (Obj) -> Boolean) {
        if (!hasExplicitStep && start is ObjInt && end is ObjInt) {
            val s = start.value
            val e = end.value
            if (isEndInclusive) {
                for (i in s..e) {
                    if (!callback(ObjInt.of(i))) break
                }
            } else {
                for (i in s..<e) {
                    if (!callback(ObjInt.of(i))) break
                }
            }
        } else if (!hasExplicitStep && start is ObjChar && end is ObjChar) {
            val s = start.value
            val e = end.value
            if (isEndInclusive) {
                for (c in s..e) {
                    if (!callback(ObjChar(c))) break
                }
            } else {
                for (c in s..<e) {
                    if (!callback(ObjChar(c))) break
                }
            }
        } else {
            super.enumerate(scope, callback)
        }
    }

    override suspend fun compareTo(scope: Scope, other: Obj): Int {
        return (other as? ObjRange)?.let {
            if (start == other.start &&
                end == other.end &&
                isEndInclusive == other.isEndInclusive &&
                step == other.step
            ) 0 else -1
        }
            ?: -1
    }

    override fun hashCode(): Int {
        var result = start?.hashCode() ?: 0
        result = 31 * result + (end?.hashCode() ?: 0)
        result = 31 * result + isEndInclusive.hashCode()
        result = 31 * result + (step?.hashCode() ?: 0)
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as ObjRange

        if (start != other.start) return false
        if (end != other.end) return false
        if (isEndInclusive != other.isEndInclusive) return false
        if (step != other.step) return false

        return true
    }


    companion object {
        val type = ObjClass("Range", ObjIterable).apply {
            addPropertyDoc(
                name = "start",
                doc = "Start bound of the range or null if open.",
                type = type("lyng.Any", nullable = true),
                moduleName = "lyng.stdlib",
                getter = { thisAs<ObjRange>().start ?: ObjNull }
            )
            addPropertyDoc(
                name = "end",
                doc = "End bound of the range or null if open.",
                type = type("lyng.Any", nullable = true),
                moduleName = "lyng.stdlib",
                getter = { thisAs<ObjRange>().end ?: ObjNull }
            )
            addPropertyDoc(
                name = "step",
                doc = "Explicit step for iteration, or null if implicit.",
                type = type("lyng.Any", nullable = true),
                moduleName = "lyng.stdlib",
                getter = { thisAs<ObjRange>().step ?: ObjNull }
            )
            addPropertyDoc(
                name = "isOpen",
                doc = "Whether the range is open on either side (no start or no end).",
                type = type("lyng.Bool"),
                moduleName = "lyng.stdlib",
                getter = { thisAs<ObjRange>().let { it.isOpenStart || it.isOpenEnd }.toObj() }
            )
            addPropertyDoc(
                name = "isIntRange",
                doc = "True if both bounds are Int values.",
                type = type("lyng.Bool"),
                moduleName = "lyng.stdlib",
                getter = { thisAs<ObjRange>().isIntRange.toObj() }
            )
            addPropertyDoc(
                name = "isCharRange",
                doc = "True if both bounds are Char values.",
                type = type("lyng.Bool"),
                moduleName = "lyng.stdlib",
                getter = { thisAs<ObjRange>().isCharRange.toObj() }
            )
            addPropertyDoc(
                name = "isEndInclusive",
                doc = "Whether the end bound is inclusive.",
                type = type("lyng.Bool"),
                moduleName = "lyng.stdlib",
                getter = { thisAs<ObjRange>().isEndInclusive.toObj() }
            )
            addFnDoc(
                name = "iterator",
                doc = "Iterator over elements in this range (optimized for Int ranges).",
                returns = TypeGenericDoc(type("lyng.Iterator"), listOf(type("lyng.Any"))),
                moduleName = "lyng.stdlib"
            ) {
                val self = thisAs<ObjRange>()
                self.buildIterator(this)
            }
        }
    }

    private fun explicitStepOrNull(): Obj? = step?.takeUnless { it.isNull }

    private suspend fun resolveStep(scope: Scope, explicitStep: Obj?): Obj {
        val startObj = start ?: ObjNull
        if (explicitStep != null) {
            if (explicitStep is Numeric && explicitStep.doubleValue == 0.0) {
                scope.raiseIllegalState("Range step cannot be zero")
            }
            if (startObj is ObjChar && explicitStep !is ObjInt) {
                scope.raiseIllegalState("Char range step must be Int")
            }
            if (startObj is Numeric && explicitStep !is Numeric) {
                scope.raiseIllegalState("Numeric range step must be numeric")
            }
            return explicitStep
        }
        if (startObj is ObjInt) {
            val cmp = if (end == null || end.isNull) 0 else startObj.compareTo(scope, end)
            val dir = if (cmp >= 0) -1 else 1
            return ObjInt.of(dir.toLong())
        }
        if (startObj is ObjChar) {
            val endChar = end as? ObjChar
                ?: scope.raiseIllegalState("Char range requires Char end to infer step")
            val dir = if (startObj.value >= endChar.value) -1 else 1
            return ObjInt.of(dir.toLong())
        }
        if (startObj is ObjReal) {
            scope.raiseIllegalState("Real range requires explicit step")
        }
        scope.raiseIllegalState("Range of ${startObj.objClass.className} requires explicit step")
    }

    private suspend fun directionMismatch(scope: Scope, step: Obj): Boolean {
        if (end == null || end.isNull) return false
        val startObj = start ?: ObjNull
        if (startObj is ObjChar && end !is ObjChar) return false
        val cmp = startObj.compareTo(scope, end)
        if (cmp == -2) return false
        if (cmp == 0) return false
        val stepSign = when {
            startObj is ObjChar && step is ObjInt -> step.value.compareTo(0)
            step is Numeric -> step.doubleValue.compareTo(0.0)
            else -> return false
        }
        return (cmp < 0 && stepSign < 0) || (cmp > 0 && stepSign > 0)
    }

    suspend fun buildIterator(scope: Scope): Obj {
        if (isOpenStart) scope.raiseIllegalState("Range with open start is not iterable")
        val explicitStep = explicitStepOrNull()
        if (isOpenEnd && explicitStep == null) {
            scope.raiseIllegalState("Open-ended range requires explicit step to iterate")
        }
        val stepValue = resolveStep(scope, explicitStep)
        val mismatch = directionMismatch(scope, stepValue)
        if (net.sergeych.lyng.PerfFlags.RANGE_FAST_ITER) {
            val s = start
            val e = end
            if (!mismatch && stepValue is ObjInt && stepValue.value == 1L && s is ObjInt && e is ObjInt) {
                val startVal = s.value.toInt()
                val endExclusive = (if (isEndInclusive) e.value.toInt() + 1 else e.value.toInt())
                if (startVal <= endExclusive) {
                    return ObjFastIntRangeIterator(startVal, endExclusive)
                }
            }
        }
        return ObjRangeIterator(this, stepValue, mismatch)
    }
}
