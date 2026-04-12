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

    override suspend fun callOn(scope: Scope): Obj {
        val resolved = read()
        if (resolved === this) {
            scope.raiseNotImplemented("call on unresolved frame slot")
        }
        return resolved.callOn(scope)
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

    internal fun resolvedCaptureValueOrNull(): Obj? {
        return when (frame.getSlotTypeCode(slot)) {
            SlotType.INT.code, SlotType.REAL.code, SlotType.BOOL.code -> read()
            else -> peekValue()?.let { read() }
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

class ScopeSlotRef(
    private val scope: Scope,
    private val slot: Int,
    private val name: String? = null,
) : net.sergeych.lyng.obj.Obj() {
    override suspend fun compareTo(scope: Scope, other: Obj): Int {
        val resolvedOther = when (other) {
            is FrameSlotRef -> other.read()
            is RecordSlotRef -> other.read()
            is ScopeSlotRef -> other.read()
            else -> other
        }
        return read().compareTo(scope, resolvedOther)
    }

    fun read(): Obj {
        val record = scope.getSlotRecord(slot)
        val direct = record.value
        if (direct is FrameSlotRef) return direct.read()
        if (direct is RecordSlotRef) return direct.read()
        if (direct is ScopeSlotRef) return direct.read()
        if (direct !== ObjUnset) {
            return direct
        }
        if (name == null) return record.value
        val resolved = scope.get(name) ?: return record.value
        if (resolved.value !== ObjUnset) {
            scope.updateSlotFor(name, resolved)
        }
        return resolved.value
    }

    internal fun peekValue(): Obj? {
        val record = scope.getSlotRecord(slot)
        val direct = record.value
        return when (direct) {
            is FrameSlotRef -> direct.peekValue()
            is RecordSlotRef -> direct.peekValue()
            is ScopeSlotRef -> direct.peekValue()
            else -> direct
        }
    }

    internal fun resolvedCaptureValueOrNull(): Obj? {
        val record = scope.getSlotRecord(slot)
        return when (val direct = record.value as Obj?) {
            is FrameSlotRef -> direct.resolvedCaptureValueOrNull()
            is RecordSlotRef -> direct.resolvedCaptureValueOrNull()
            is ScopeSlotRef -> direct.resolvedCaptureValueOrNull()
            else -> direct
        }
    }

    fun write(value: Obj) {
        scope.setSlotValue(slot, value)
    }

    override suspend fun callOn(scope: Scope): Obj {
        val resolved = read()
        if (resolved === this) {
            scope.raiseNotImplemented("call on unresolved scope slot")
        }
        return resolved.callOn(scope)
    }
}

class RecordSlotRef(
    private val record: ObjRecord,
) : net.sergeych.lyng.obj.Obj() {
    override suspend fun compareTo(scope: Scope, other: Obj): Int {
        val resolvedOther = when (other) {
            is FrameSlotRef -> other.read()
            is RecordSlotRef -> other.read()
            is ScopeSlotRef -> other.read()
            else -> other
        }
        return read().compareTo(scope, resolvedOther)
    }

    fun read(): Obj {
        val direct = record.value
        return when (direct) {
            is FrameSlotRef -> direct.read()
            is ScopeSlotRef -> direct.read()
            else -> direct
        }
    }

    suspend fun read(scope: Scope, name: String?): Obj {
        val direct = record.value
        if (name != null && (record.type == ObjRecord.Type.Delegated || record.type == ObjRecord.Type.Property || direct is ObjProperty)) {
            return scope.resolve(record, name)
        }
        return when (direct) {
            is FrameSlotRef -> direct.read()
            is RecordSlotRef -> direct.read(scope, name)
            is ScopeSlotRef -> direct.read()
            else -> direct
        }
    }

    override suspend fun callOn(scope: Scope): Obj {
        val resolved = read()
        if (resolved === this) {
            scope.raiseNotImplemented("call on unresolved record slot")
        }
        return resolved.callOn(scope)
    }

    internal fun peekValue(): Obj? {
        val direct = record.value
        return when (direct) {
            is FrameSlotRef -> direct.peekValue()
            is RecordSlotRef -> direct.peekValue()
            is ScopeSlotRef -> direct.peekValue()
            else -> direct
        }
    }

    internal fun resolvedCaptureValueOrNull(): Obj? {
        return when (val direct = record.value as Obj?) {
            is FrameSlotRef -> direct.resolvedCaptureValueOrNull()
            is RecordSlotRef -> direct.resolvedCaptureValueOrNull()
            is ScopeSlotRef -> direct.resolvedCaptureValueOrNull()
            else -> direct
        }
    }

    fun write(value: Obj) {
        val direct = record.value
        if (direct is ScopeSlotRef) {
            direct.write(value)
        } else {
            record.value = value
        }
    }

    suspend fun write(scope: Scope, name: String?, value: Obj): Boolean {
        val direct = record.value
        if (name != null && (record.type == ObjRecord.Type.Delegated || record.type == ObjRecord.Type.Property || direct is ObjProperty)) {
            scope.assign(record, name, value)
            return true
        }
        when (direct) {
            is ScopeSlotRef -> direct.write(value)
            is RecordSlotRef -> if (direct.write(scope, name, value)) return true else direct.write(value)
            else -> record.value = value
        }
        return false
    }
}
