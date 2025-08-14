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

class ObjRangeIterator(val self: ObjRange) : Obj() {

    private var nextIndex = 0
    private var lastIndex = 0
    private var isCharRange: Boolean = false

    override val objClass: ObjClass = type

    fun Scope.init() {
        if (self.start == null || self.end == null)
            raiseError("next is only available for finite ranges")
        isCharRange = self.isCharRange
        lastIndex = if (self.isIntRange || self.isCharRange) {
            if (self.isEndInclusive)
                self.end.toInt() - self.start.toInt() + 1
            else
                self.end.toInt() - self.start.toInt()
        } else {
            raiseError("not implemented iterator for range of $this")
        }
    }

    fun hasNext(): Boolean = nextIndex < lastIndex

    fun next(scope: Scope): Obj =
        if (nextIndex < lastIndex) {
            val x = if (self.isEndInclusive)
                self.start!!.toLong() + nextIndex++
            else
                self.start!!.toLong() + nextIndex++
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