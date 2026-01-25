/*
 * Copyright 2026 Sergey S. Chernov
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
 */

package net.sergeych.lyng.bytecode

import net.sergeych.lyng.obj.Obj

sealed class BytecodeConst {
    object Null : BytecodeConst()
    data class Bool(val value: Boolean) : BytecodeConst()
    data class IntVal(val value: Long) : BytecodeConst()
    data class RealVal(val value: Double) : BytecodeConst()
    data class StringVal(val value: String) : BytecodeConst()
    data class ObjRef(val value: Obj) : BytecodeConst()
}
