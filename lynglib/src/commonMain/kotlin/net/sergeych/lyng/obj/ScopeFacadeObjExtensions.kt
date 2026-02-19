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

import net.sergeych.lyng.Pos
import net.sergeych.lyng.Scope
import net.sergeych.lyng.ScopeFacade
import net.sergeych.lyng.raiseAssertionFailed as coreRaiseAssertionFailed
import net.sergeych.lyng.raiseError as coreRaiseError
import net.sergeych.lyng.raiseIllegalAssignment as coreRaiseIllegalAssignment
import net.sergeych.lyng.raiseIllegalOperation as coreRaiseIllegalOperation
import net.sergeych.lyng.raiseIndexOutOfBounds as coreRaiseIndexOutOfBounds
import net.sergeych.lyng.raiseIterationFinished as coreRaiseIterationFinished
import net.sergeych.lyng.raiseNPE as coreRaiseNPE
import net.sergeych.lyng.raiseNotFound as coreRaiseNotFound
import net.sergeych.lyng.raiseUnset as coreRaiseUnset
import net.sergeych.lyng.requireExactCount as coreRequireExactCount
import net.sergeych.lyng.requireNoArgs as coreRequireNoArgs
import net.sergeych.lyng.requireOnlyArg as coreRequireOnlyArg
import net.sergeych.lyng.requireScope as coreRequireScope
import net.sergeych.lyng.requiredArg as coreRequiredArg
import net.sergeych.lyng.thisAs as coreThisAs

inline fun <reified T : Obj> ScopeFacade.requiredArg(index: Int): T = coreRequiredArg(index)

inline fun <reified T : Obj> ScopeFacade.requireOnlyArg(): T = coreRequireOnlyArg()

fun ScopeFacade.requireExactCount(count: Int) = coreRequireExactCount(count)

fun ScopeFacade.requireNoArgs() = coreRequireNoArgs()

inline fun <reified T : Obj> ScopeFacade.thisAs(): T = coreThisAs()

internal fun ScopeFacade.requireScope(): Scope = coreRequireScope()

fun ScopeFacade.raiseNPE(): Nothing = coreRaiseNPE()

fun ScopeFacade.raiseIndexOutOfBounds(message: String = "Index out of bounds"): Nothing =
    coreRaiseIndexOutOfBounds(message)

fun ScopeFacade.raiseIllegalAssignment(message: String): Nothing =
    coreRaiseIllegalAssignment(message)

fun ScopeFacade.raiseUnset(message: String = "property is unset (not initialized)"): Nothing =
    coreRaiseUnset(message)

fun ScopeFacade.raiseNotFound(message: String = "not found"): Nothing =
    coreRaiseNotFound(message)

fun ScopeFacade.raiseError(obj: Obj, pos: Pos = this.pos, message: String): Nothing =
    coreRaiseError(obj, pos, message)

fun ScopeFacade.raiseAssertionFailed(message: String): Nothing =
    coreRaiseAssertionFailed(message)

fun ScopeFacade.raiseIllegalOperation(message: String = "Operation is illegal"): Nothing =
    coreRaiseIllegalOperation(message)

fun ScopeFacade.raiseIterationFinished(): Nothing =
    coreRaiseIterationFinished()
