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

package net.sergeych.lyng.bytecode

data class CmdFunction(
    val name: String,
    val localCount: Int,
    val addrCount: Int,
    val returnLabels: Set<String>,
    val scopeSlotCount: Int,
    val scopeSlotIndices: IntArray,
    val scopeSlotNames: Array<String?>,
    val scopeSlotIsModule: BooleanArray,
    val localSlotNames: Array<String?>,
    val localSlotMutables: BooleanArray,
    val localSlotDelegated: BooleanArray,
    val localSlotCaptures: BooleanArray,
    val constants: List<BytecodeConst>,
    val cmds: Array<Cmd>,
    val posByIp: Array<net.sergeych.lyng.Pos?>,
) {
    init {
        require(scopeSlotIndices.size == scopeSlotCount) { "scopeSlotIndices size mismatch" }
        require(scopeSlotNames.size == scopeSlotCount) { "scopeSlotNames size mismatch" }
        require(scopeSlotIsModule.size == scopeSlotCount) { "scopeSlotIsModule size mismatch" }
        require(localSlotNames.size == localSlotMutables.size) { "localSlot metadata size mismatch" }
        require(localSlotNames.size == localSlotDelegated.size) { "localSlot delegation size mismatch" }
        require(localSlotNames.size == localSlotCaptures.size) { "localSlot capture size mismatch" }
        require(localSlotNames.size <= localCount) { "localSlotNames exceed localCount" }
        require(addrCount >= 0) { "addrCount must be non-negative" }
        if (posByIp.isNotEmpty()) {
            require(posByIp.size == cmds.size) { "posByIp size mismatch" }
        }
    }

    fun localSlotPlanByName(): Map<String, Int> {
        val result = LinkedHashMap<String, Int>()
        for (i in localSlotNames.indices) {
            val name = localSlotNames[i] ?: continue
            val existing = result[name]
            if (existing == null) {
                result[name] = i
                continue
            }
            val existingIsCapture = localSlotCaptures.getOrNull(existing) == true
            val currentIsCapture = localSlotCaptures.getOrNull(i) == true
            if (existingIsCapture && !currentIsCapture) {
                result[name] = i
            }
        }
        return result
    }

}
