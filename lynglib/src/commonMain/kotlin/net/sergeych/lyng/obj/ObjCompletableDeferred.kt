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
import net.sergeych.lyng.Scope

class ObjCompletableDeferred(val completableDeferred: CompletableDeferred<Obj>): ObjDeferred(completableDeferred) {

    override val objClass = type

    companion object {
        val type = object: ObjClass("CompletableDeferred", ObjDeferred.type){
            override suspend fun callOn(scope: Scope): Obj {
                return ObjCompletableDeferred(CompletableDeferred())
            }
        }.apply {
            addFn("complete") {
                thisAs<ObjCompletableDeferred>().completableDeferred.complete(args.firstAndOnly())
                ObjVoid
            }
        }
    }
}