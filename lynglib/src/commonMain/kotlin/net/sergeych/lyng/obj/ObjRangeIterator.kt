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

class ObjRangeIterator(
    private val self: ObjRange,
    private val step: Obj,
    private val directionMismatch: Boolean
) : Obj() {

    private var current: Obj? = null
    private var initialized = false

    override val objClass: ObjClass get() = type

    private fun ensureInit() {
        if (!initialized) {
            current = self.start ?: ObjNull
            initialized = true
        }
    }

    suspend fun hasNext(scope: Scope): Boolean {
        if (directionMismatch) return false
        ensureInit()
        val cur = current ?: return false
        return self.contains(scope, cur)
    }

    suspend fun next(scope: Scope): Obj {
        if (!hasNext(scope)) {
            scope.raiseError(ObjIterationFinishedException(scope))
        }
        val result = current ?: scope.raiseError("iterator error: missing current value")
        current = advance(scope, result)
        return result
    }

    private suspend fun advance(scope: Scope, value: Obj): Obj =
        if (value is ObjChar) {
            val delta = (step as? ObjInt)
                ?: scope.raiseIllegalState("Char range step must be Int")
            ObjChar((value.value.code + delta.value.toInt()).toChar())
        } else {
            value.plus(scope, step)
        }

    companion object {
        val type = ObjClass("RangeIterator", ObjIterator).apply {
            addFn("hasNext") {
                thisAs<ObjRangeIterator>().hasNext(requireScope()).toObj()
            }
            addFn("next") {
                thisAs<ObjRangeIterator>().next(requireScope())
            }
        }
    }
}

/**
 * Fast iterator for simple integer ranges (step +1). Returned only when
 * [PerfFlags.RANGE_FAST_ITER] is enabled and the range is an ascending int range.
 */
class ObjFastIntRangeIterator(private val start: Int, private val endExclusive: Int) : Obj() {

    private var cur: Int = start
    private val cacheLow = ObjInt.CACHE_LOW.toInt()
    private val useCache = start >= cacheLow && endExclusive <= ObjInt.CACHE_HIGH.toInt() + 1
    private val cache = if (useCache) ObjInt.cacheArray() else null

    override val objClass: ObjClass get() = type

    fun hasNext(): Boolean = cur < endExclusive

    fun next(scope: Scope): Obj =
        if (cur < endExclusive) {
            if (useCache && cache != null) cache[cur++ - cacheLow] else ObjInt(cur++.toLong())
        }
        else scope.raiseError(ObjIterationFinishedException(scope))

    companion object {
        val type = ObjClass("FastIntRangeIterator", ObjIterator).apply {
            addFn("hasNext") { thisAs<ObjFastIntRangeIterator>().hasNext().toObj() }
            addFn("next") { thisAs<ObjFastIntRangeIterator>().next(requireScope()) }
        }
    }
}
