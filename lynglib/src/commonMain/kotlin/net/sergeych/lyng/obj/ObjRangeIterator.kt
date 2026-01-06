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

import net.sergeych.lyng.PerfFlags
import net.sergeych.lyng.Scope

class ObjRangeIterator(val self: ObjRange) : Obj() {

    private var nextIndex = 0
    private var lastIndex = 0
    private var isCharRange: Boolean = false

    override val objClass: ObjClass = type

    fun Scope.init() {
        val s = self.start
        val e = self.end
        if (s is ObjInt && e is ObjInt) {
            lastIndex = if (self.isEndInclusive)
                (e.value - s.value + 1).toInt()
            else
                (e.value - s.value).toInt()
        } else if (s is ObjChar && e is ObjChar) {
            isCharRange = true
            lastIndex = if (self.isEndInclusive)
                (e.value.code - s.value.code + 1)
            else
                (e.value.code - s.value.code)
        } else {
            raiseError("not implemented iterator for range of $this")
        }
    }

    fun hasNext(): Boolean = nextIndex < lastIndex

    fun next(scope: Scope): Obj =
        if (nextIndex < lastIndex) {
            val start = self.start
            val x = if (start is ObjInt)
                start.value + nextIndex++
            else if (start is ObjChar)
                start.value.code.toLong() + nextIndex++
            else
                scope.raiseError("iterator error: unsupported range start")
            if( isCharRange ) ObjChar(x.toInt().toChar()) else ObjInt(x)
        }
        else {
            scope.raiseError(ObjIterationFinishedException(scope))
        }

    companion object {
        val type = ObjClass("RangeIterator", ObjIterator).apply {
            addFn("hasNext") {
                thisAs<ObjRangeIterator>().hasNext().toObj()
            }
            addFn("next") {
                thisAs<ObjRangeIterator>().next(this)
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

    override val objClass: ObjClass = type

    fun hasNext(): Boolean = cur < endExclusive

    fun next(scope: Scope): Obj =
        if (cur < endExclusive) ObjInt(cur++.toLong())
        else scope.raiseError(ObjIterationFinishedException(scope))

    companion object {
        val type = ObjClass("FastIntRangeIterator", ObjIterator).apply {
            addFn("hasNext") { thisAs<ObjFastIntRangeIterator>().hasNext().toObj() }
            addFn("next") { thisAs<ObjFastIntRangeIterator>().next(this) }
        }
    }
}