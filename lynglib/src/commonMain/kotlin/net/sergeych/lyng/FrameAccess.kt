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

package net.sergeych.lyng

import net.sergeych.lyng.bytecode.SlotType
import net.sergeych.lyng.obj.*

interface FrameAccess {
    fun getSlotTypeCode(slot: Int): Byte
    fun getObj(slot: Int): Obj
    fun getInt(slot: Int): Long
    fun getReal(slot: Int): Double
    fun getBool(slot: Int): Boolean
    fun setObj(slot: Int, value: Obj)
    fun setInt(slot: Int, value: Long)
    fun setReal(slot: Int, value: Double)
    fun setBool(slot: Int, value: Boolean)
}

class FrameSlotRef(
    private val frame: FrameAccess,
    private val slot: Int,
) : net.sergeych.lyng.obj.Obj() {
    override suspend fun compareTo(scope: Scope, other: Obj): Int {
        val resolvedOther = when (other) {
            is FrameSlotRef -> other.read()
            is RecordSlotRef -> other.read()
            else -> other
        }
        return read().compareTo(scope, resolvedOther)
    }

    fun read(): Obj {
        val typeCode = frame.getSlotTypeCode(slot)
        return when (typeCode) {
            SlotType.INT.code -> ObjInt.of(frame.getInt(slot))
            SlotType.REAL.code -> ObjReal.of(frame.getReal(slot))
            SlotType.BOOL.code -> if (frame.getBool(slot)) ObjTrue else ObjFalse
            SlotType.OBJ.code -> frame.getObj(slot)
            else -> frame.getObj(slot)
        }
    }

    internal fun refersTo(frame: FrameAccess, slot: Int): Boolean {
        return this.frame === frame && this.slot == slot
    }

    internal fun peekValue(): Obj? {
        val bytecodeFrame = frame as? net.sergeych.lyng.bytecode.BytecodeFrame ?: return read()
        val raw = bytecodeFrame.getRawObj(slot) ?: return null
        if (raw is FrameSlotRef && raw.refersTo(bytecodeFrame, slot)) return null
        return when (raw) {
            is FrameSlotRef -> raw.peekValue()
            is RecordSlotRef -> raw.peekValue()
            else -> raw
        }
    }

    fun write(value: Obj) {
        when (value) {
            is ObjInt -> frame.setInt(slot, value.value)
            is ObjReal -> frame.setReal(slot, value.value)
            is ObjBool -> frame.setBool(slot, value.value)
            else -> frame.setObj(slot, value)
        }
    }
}

class RecordSlotRef(
    private val record: ObjRecord,
) : net.sergeych.lyng.obj.Obj() {
    override suspend fun compareTo(scope: Scope, other: Obj): Int {
        val resolvedOther = when (other) {
            is FrameSlotRef -> other.read()
            is RecordSlotRef -> other.read()
            else -> other
        }
        return read().compareTo(scope, resolvedOther)
    }

    fun read(): Obj {
        val direct = record.value
        return if (direct is FrameSlotRef) direct.read() else direct
    }

    internal fun peekValue(): Obj? {
        val direct = record.value
        return when (direct) {
            is FrameSlotRef -> direct.peekValue()
            is RecordSlotRef -> direct.peekValue()
            else -> direct
        }
    }

    fun write(value: Obj) {
        record.value = value
    }
}
