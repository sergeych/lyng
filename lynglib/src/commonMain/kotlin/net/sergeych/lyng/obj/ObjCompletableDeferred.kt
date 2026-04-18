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

import kotlinx.coroutines.CompletableDeferred
import net.sergeych.lyng.ExecutionError
import net.sergeych.lyng.Scope
import net.sergeych.lyng.miniast.ParamDoc
import net.sergeych.lyng.miniast.addFnDoc
import net.sergeych.lyng.miniast.type

class ObjCompletableDeferred(val completableDeferred: CompletableDeferred<Obj>): ObjDeferred(completableDeferred) {

    override val objClass get() = type

    companion object {
        val type = object: ObjClass("CompletableDeferred", ObjDeferred.type){
            override suspend fun callOn(scope: Scope): Obj {
                return ObjCompletableDeferred(CompletableDeferred())
            }
        }.apply {
            addFnDoc(
                name = "complete",
                doc = "Complete this deferred with the given value. Subsequent calls have no effect.",
                params = listOf(ParamDoc("value")),
                returns = type("lyng.Void"),
                moduleName = "lyng.stdlib"
            ) {
                thisAs<ObjCompletableDeferred>().completableDeferred.complete(args.firstAndOnly())
                ObjVoid
            }
            addFnDoc(
                name = "completeExceptionally",
                doc = "Fail this deferred with the given exception. Awaiting it will then throw that exception. " +
                    "Subsequent calls have no effect. The argument must be an `Exception` instance.",
                params = listOf(ParamDoc("exception", type("lyng.Exception"))),
                returns = type("lyng.Void"),
                moduleName = "lyng.stdlib"
            ) {
                val ex = requiredArg<Obj>(0)
                val scope = requireScope()
                val msg = when (ex) {
                    is ObjException -> ex.message.value
                    else -> ex.toString(scope).value
                }
                val pos = when (ex) {
                    is ObjException -> ex.scope.pos
                    else -> scope.pos
                }
                // Always carry the original Lyng object as errorObject so that
                // assertThrows / catch clauses see the correct exception class.
                val cause = ExecutionError(ex, pos, msg)
                thisAs<ObjCompletableDeferred>().completableDeferred.completeExceptionally(cause)
                ObjVoid
            }
        }
    }
}