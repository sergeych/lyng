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

import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjNull

class BytecodeFrame(
    val localCount: Int,
    val argCount: Int,
) {
    val slotCount: Int = localCount + argCount
    val argBase: Int = localCount

    private val slotTypes: ByteArray = ByteArray(slotCount) { SlotType.UNKNOWN.code }
    private val objSlots: Array<Obj?> = arrayOfNulls(slotCount)
    private val intSlots: LongArray = LongArray(slotCount)
    private val realSlots: DoubleArray = DoubleArray(slotCount)
    private val boolSlots: BooleanArray = BooleanArray(slotCount)

    fun getSlotType(slot: Int): SlotType = SlotType.values().first { it.code == slotTypes[slot] }
    fun setSlotType(slot: Int, type: SlotType) {
        slotTypes[slot] = type.code
    }

    fun getObj(slot: Int): Obj = objSlots[slot] ?: ObjNull
    fun setObj(slot: Int, value: Obj) {
        objSlots[slot] = value
        slotTypes[slot] = SlotType.OBJ.code
    }

    fun getInt(slot: Int): Long = intSlots[slot]
    fun setInt(slot: Int, value: Long) {
        intSlots[slot] = value
        slotTypes[slot] = SlotType.INT.code
    }

    fun getReal(slot: Int): Double = realSlots[slot]
    fun setReal(slot: Int, value: Double) {
        realSlots[slot] = value
        slotTypes[slot] = SlotType.REAL.code
    }

    fun getBool(slot: Int): Boolean = boolSlots[slot]
    fun setBool(slot: Int, value: Boolean) {
        boolSlots[slot] = value
        slotTypes[slot] = SlotType.BOOL.code
    }

    fun clearSlot(slot: Int) {
        slotTypes[slot] = SlotType.UNKNOWN.code
        objSlots[slot] = null
        intSlots[slot] = 0L
        realSlots[slot] = 0.0
        boolSlots[slot] = false
    }
}
