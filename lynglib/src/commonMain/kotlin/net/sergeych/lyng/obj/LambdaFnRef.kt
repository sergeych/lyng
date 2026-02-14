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
 */

package net.sergeych.lyng.obj

import net.sergeych.lyng.ArgsDeclaration
import net.sergeych.lyng.Pos
import net.sergeych.lyng.Scope
import net.sergeych.lyng.bytecode.CmdFunction
import net.sergeych.lyng.bytecode.LambdaCaptureEntry

class LambdaFnRef(
    valueFn: suspend (Scope) -> ObjRecord,
    val bytecodeFn: CmdFunction?,
    val paramSlotPlan: Map<String, Int>,
    val argsDeclaration: ArgsDeclaration?,
    val captureEntries: List<LambdaCaptureEntry>,
    val preferredThisType: String?,
    val wrapAsExtensionCallable: Boolean,
    val returnLabels: Set<String>,
    val pos: Pos,
) : ValueFnRef(valueFn)
