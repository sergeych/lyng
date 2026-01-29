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

package net.sergeych.lyng

import net.sergeych.lyng.obj.ListLiteralRef
import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjVoid

class DestructuringVarDeclStatement(
    val pattern: ListLiteralRef,
    val names: List<String>,
    val initializer: Statement,
    val isMutable: Boolean,
    val visibility: Visibility,
    val isTransient: Boolean,
    override val pos: Pos,
) : Statement() {
    override suspend fun execute(context: Scope): Obj {
        val value = initializer.execute(context)
        for (name in names) {
            context.addItem(name, true, ObjVoid, visibility, isTransient = isTransient)
        }
        pattern.setAt(pos, context, value)
        if (!isMutable) {
            for (name in names) {
                val rec = context.objects[name]!!
                val immutableRec = rec.copy(isMutable = false)
                context.objects[name] = immutableRec
                context.localBindings[name] = immutableRec
                context.updateSlotFor(name, immutableRec)
            }
        }
        return ObjVoid
    }
}
