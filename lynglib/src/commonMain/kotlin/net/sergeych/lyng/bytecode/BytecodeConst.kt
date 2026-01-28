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

import net.sergeych.lyng.Pos
import net.sergeych.lyng.Visibility
import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjProperty

sealed class BytecodeConst {
    object Null : BytecodeConst()
    data class Bool(val value: Boolean) : BytecodeConst()
    data class IntVal(val value: Long) : BytecodeConst()
    data class RealVal(val value: Double) : BytecodeConst()
    data class StringVal(val value: String) : BytecodeConst()
    data class PosVal(val pos: Pos) : BytecodeConst()
    data class ObjRef(val value: Obj) : BytecodeConst()
    data class Ref(val value: net.sergeych.lyng.obj.ObjRef) : BytecodeConst()
    data class StatementVal(val statement: net.sergeych.lyng.Statement) : BytecodeConst()
    data class ListLiteralPlan(val spreads: List<Boolean>) : BytecodeConst()
    data class ValueFn(val fn: suspend (net.sergeych.lyng.Scope) -> net.sergeych.lyng.obj.ObjRecord) : BytecodeConst()
    data class SlotPlan(val plan: Map<String, Int>) : BytecodeConst()
    data class ExtensionPropertyDecl(
        val extTypeName: String,
        val property: ObjProperty,
        val visibility: Visibility,
        val setterVisibility: Visibility?,
    ) : BytecodeConst()
    data class LocalDecl(
        val name: String,
        val isMutable: Boolean,
        val visibility: Visibility,
        val isTransient: Boolean,
    ) : BytecodeConst()
    data class CallArgsPlan(val tailBlock: Boolean, val specs: List<CallArgSpec>) : BytecodeConst()
    data class CallArgSpec(val name: String?, val isSplat: Boolean)
}
