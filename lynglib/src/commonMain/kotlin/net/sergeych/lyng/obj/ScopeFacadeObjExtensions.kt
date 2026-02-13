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

package net.sergeych.lyng.obj

import net.sergeych.lyng.Scope
import net.sergeych.lyng.ScopeFacade
import net.sergeych.lyng.requireExactCount as coreRequireExactCount
import net.sergeych.lyng.requireNoArgs as coreRequireNoArgs
import net.sergeych.lyng.requireOnlyArg as coreRequireOnlyArg
import net.sergeych.lyng.requiredArg as coreRequiredArg
import net.sergeych.lyng.requireScope as coreRequireScope
import net.sergeych.lyng.thisAs as coreThisAs

inline fun <reified T : Obj> ScopeFacade.requiredArg(index: Int): T = coreRequiredArg(index)

inline fun <reified T : Obj> ScopeFacade.requireOnlyArg(): T = coreRequireOnlyArg()

fun ScopeFacade.requireExactCount(count: Int) = coreRequireExactCount(count)

fun ScopeFacade.requireNoArgs() = coreRequireNoArgs()

inline fun <reified T : Obj> ScopeFacade.thisAs(): T = coreThisAs()

internal fun ScopeFacade.requireScope(): Scope = coreRequireScope()
