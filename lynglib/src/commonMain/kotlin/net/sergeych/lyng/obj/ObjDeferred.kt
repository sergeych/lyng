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

import kotlinx.coroutines.Deferred
import net.sergeych.lyng.Scope
import net.sergeych.lyng.ScopeCallable
import net.sergeych.lyng.miniast.addFnDoc
import net.sergeych.lyng.miniast.addPropertyDoc
import net.sergeych.lyng.miniast.type

open class ObjDeferred(val deferred: Deferred<Obj>): Obj() {

    override val objClass get() = type

    companion object {
        val type = object: ObjClass("Deferred"){
            override suspend fun callOn(scope: Scope): Obj {
                scope.raiseError("Deferred constructor is not directly callable")
            }
        }.apply {
            addFnDoc(
                name = "await",
                doc = "Suspend until completion and return the result value (or throw if failed).",
                returns = type("lyng.Any"),
                moduleName = "lyng.stdlib",
                code = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj = scp.thisAs<ObjDeferred>().deferred.await()
                }
            )
            addPropertyDoc(
                name = "isCompleted",
                doc = "Whether this deferred has completed (successfully or with an error).",
                type = type("lyng.Bool"),
                moduleName = "lyng.stdlib",
                getter = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj = scp.thisAs<ObjDeferred>().deferred.isCompleted.toObj()
                }
            )
            addPropertyDoc(
                name = "isActive",
                doc = "Whether this deferred is currently active (not completed and not cancelled).",
                type = type("lyng.Bool"),
                moduleName = "lyng.stdlib",
                getter = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj {
                        val d = scp.thisAs<ObjDeferred>().deferred
                        return (d.isActive || (!d.isCompleted && !d.isCancelled)).toObj()
                    }
                }
            )
            addPropertyDoc(
                name = "isCancelled",
                doc = "Whether this deferred was cancelled.",
                type = type("lyng.Bool"),
                moduleName = "lyng.stdlib",
                getter = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj = scp.thisAs<ObjDeferred>().deferred.isCancelled.toObj()
                }
            )
        }
    }
}

