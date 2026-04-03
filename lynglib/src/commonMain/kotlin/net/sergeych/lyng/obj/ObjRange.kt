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
import net.sergeych.lyng.requireScope

class ObjRange(
    val start: Obj?,
    val end: Obj?,
    val isEndInclusive: Boolean,
    val isDescending: Boolean = false,
    val step: Obj? = null
) : Obj() {

    val isOpenStart by lazy { start == null || start.isNull }
    val isOpenEnd by lazy { end == null || end.isNull }
    val hasExplicitStep: Boolean get() = step != null && !step.isNull

    override val objClass: ObjClass get() = type

    override suspend fun defaultToString(scope: Scope): ObjString {
        val result = StringBuilder()
        result.append(start?.inspect(scope) ?: "∞")
        when {
            isDescending && isEndInclusive -> result.append(" downTo ")
            isDescending && !isEndInclusive -> result.append(" downUntil ")
            else -> {
                result.append(" ..")
                if (!isEndInclusive) result.append('<')
                result.append(' ')
            }
        }
        result.append(end?.inspect(scope) ?: "∞")
        if (hasExplicitStep) {
            result.append(" step ${step?.inspect(scope)}")
        }
        return ObjString(result.toString())
    }

    private data class NormalizedLowerBound(val value: Obj, val inclusive: Boolean)
    private data class NormalizedUpperBound(val value: Obj, val inclusive: Boolean)

    private fun normalizedLowerBound(): NormalizedLowerBound? =
        when {
            isDescending -> end?.takeUnless { it.isNull }?.let { NormalizedLowerBound(it, isEndInclusive) }
            else -> start?.takeUnless { it.isNull }?.let { NormalizedLowerBound(it, true) }
        }

    private fun normalizedUpperBound(): NormalizedUpperBound? =
        when {
            isDescending -> start?.takeUnless { it.isNull }?.let { NormalizedUpperBound(it, true) }
            else -> end?.takeUnless { it.isNull }?.let { NormalizedUpperBound(it, isEndInclusive) }
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
        val ourLower = normalizedLowerBound()
        val otherLower = other.normalizedLowerBound()
        if (ourLower != null) {
            if (otherLower == null) return false
            val cmp = ourLower.value.compareTo(scope, otherLower.value)
            if (cmp == -2 || cmp > 0) return false
            if (cmp == 0 && otherLower.inclusive && !ourLower.inclusive) return false
        }
        val ourUpper = normalizedUpperBound()
        val otherUpper = other.normalizedUpperBound()
        if (ourUpper != null) {
            if (otherUpper == null) return false
            val cmp = ourUpper.value.compareTo(scope, otherUpper.value)
            if (cmp == -2 || cmp < 0) return false
            if (cmp == 0 && otherUpper.inclusive && !ourUpper.inclusive) return false
        }
        return true
    }

    override suspend fun contains(scope: Scope, other: Obj): Boolean {

        if (other is ObjRange)
            return containsRange(scope, other)

        if (net.sergeych.lyng.PerfFlags.PRIMITIVE_FASTOPS) {
            if (start is ObjInt && end is ObjInt && other is ObjInt) {
                val lower = if (isDescending) end.value else start.value
                val upper = if (isDescending) start.value else end.value
                val v = other.value
                if (v < lower || v > upper) return false
                return if (isDescending) v != lower || isEndInclusive else v != upper || isEndInclusive
            }
            if (start is ObjChar && end is ObjChar && other is ObjChar) {
                val lower = if (isDescending) end.value else start.value
                val upper = if (isDescending) start.value else end.value
                val v = other.value
                if (v < lower || v > upper) return false
                return if (isDescending) v != lower || isEndInclusive else v != upper || isEndInclusive
            }
            if (start is ObjString && end is ObjString && other is ObjString) {
                val lower = if (isDescending) end.value else start.value
                val upper = if (isDescending) start.value else end.value
                val v = other.value
                if (v < lower || v > upper) return false
                return if (isDescending) v != lower || isEndInclusive else v != upper || isEndInclusive
            }
        }

        val lower = normalizedLowerBound()
        val upper = normalizedUpperBound()
        if (lower == null && upper == null) return true
        if (lower != null) {
            val cmp = lower.value.compareTo(scope, other)
            if (cmp == -2 || cmp > 0 || (!lower.inclusive && cmp == 0)) return false
        }
        if (upper != null) {
            val cmp = upper.value.compareTo(scope, other)
            if (cmp == -2 || cmp < 0 || (!upper.inclusive && cmp == 0)) return false
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
            if (isDescending) {
                val last = if (isEndInclusive) e else e + 1
                for (i in s downTo last) {
                    if (!callback(ObjInt.of(i))) break
                }
            } else if (isEndInclusive) {
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
            if (isDescending) {
                var c = s.code
                val last = if (isEndInclusive) e.code else e.code + 1
                while (c >= last) {
                    if (!callback(ObjChar(c.toChar()))) break
                    c--
                }
            } else if (isEndInclusive) {
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
                isDescending == other.isDescending &&
                step == other.step
            ) 0 else -1
        }
            ?: -1
    }

    override fun hashCode(): Int {
        var result = start?.hashCode() ?: 0
        result = 31 * result + (end?.hashCode() ?: 0)
        result = 31 * result + isEndInclusive.hashCode()
        result = 31 * result + isDescending.hashCode()
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
        if (isDescending != other.isDescending) return false
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
            addPropertyDoc(
                name = "isDescending",
                doc = "Whether the range iterates from the start bound down toward the end bound.",
                type = type("lyng.Bool"),
                moduleName = "lyng.stdlib",
                getter = { thisAs<ObjRange>().isDescending.toObj() }
            )
            addFnDoc(
                name = "iterator",
                doc = "Iterator over elements in this range (optimized for Int ranges).",
                returns = TypeGenericDoc(type("lyng.Iterator"), listOf(type("lyng.Any"))),
                moduleName = "lyng.stdlib"
            ) {
                val self = thisAs<ObjRange>()
                self.buildIterator(requireScope())
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
            if (isDescending) {
                val sign = when (explicitStep) {
                    is ObjInt -> explicitStep.value.compareTo(0)
                    is Numeric -> explicitStep.doubleValue.compareTo(0.0)
                    else -> 1
                }
                if (sign < 0) scope.raiseIllegalState("Descending range step must be positive")
                return explicitStep.negate(scope)
            }
            return explicitStep
        }
        if (startObj is ObjInt) {
            return ObjInt.of(if (isDescending) -1 else 1)
        }
        if (startObj is ObjChar) {
            return ObjInt.of(if (isDescending) -1 else 1)
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
