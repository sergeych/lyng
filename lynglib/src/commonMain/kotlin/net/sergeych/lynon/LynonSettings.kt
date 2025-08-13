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

package net.sergeych.lynon

import net.sergeych.lyng.obj.ObjBool
import net.sergeych.lyng.obj.ObjChar
import net.sergeych.lyng.obj.ObjInt
import net.sergeych.lyng.obj.ObjNull
import kotlin.math.absoluteValue

open class LynonSettings {
    enum class InstantTruncateMode {
        Second,
        Millisecond,
        Microsecond
    }

    open fun shouldCache(obj: Any): Boolean = when (obj) {
        is ObjChar -> false
        is ObjInt -> obj.value.absoluteValue > 0x10000FF
        is ObjBool -> false
        is ObjNull -> false
        is ByteArray -> obj.size > 2
        is UByteArray -> obj.size > 2
        else -> true
    }

    companion object {
        val default = LynonSettings()
    }
}