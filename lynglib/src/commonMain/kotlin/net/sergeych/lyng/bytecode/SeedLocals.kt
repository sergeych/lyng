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

import net.sergeych.lyng.Scope
import net.sergeych.lyng.obj.ObjRecord

internal suspend fun seedFrameLocalsFromScope(frame: CmdFrame, scope: Scope) {
    val localNames = frame.fn.localSlotNames
    if (localNames.isEmpty()) return
    val base = frame.fn.scopeSlotCount
    for (i in localNames.indices) {
        val name = localNames[i] ?: continue
        val slotType = frame.getLocalSlotTypeCode(i)
        if (slotType != SlotType.UNKNOWN.code && slotType != SlotType.OBJ.code) continue
        if (slotType == SlotType.OBJ.code && frame.frame.getRawObj(i) != null) continue
        val record = scope.getLocalRecordDirect(name)
            ?: scope.chainLookupIgnoreClosure(name, followClosure = true)
            ?: continue
        val value = if (record.type == ObjRecord.Type.Delegated || record.type == ObjRecord.Type.Property) {
            scope.resolve(record, name)
        } else {
            record.value
        }
        if (value is net.sergeych.lyng.FrameSlotRef && value.refersTo(frame.frame, base + i)) {
            continue
        }
        frame.setObjUnchecked(base + i, value)
    }
}
