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

data class CmdFunction(
    val name: String,
    val localCount: Int,
    val addrCount: Int,
    val scopeSlotCount: Int,
    val scopeSlotDepths: IntArray,
    val scopeSlotIndices: IntArray,
    val scopeSlotNames: Array<String?>,
    val localSlotNames: Array<String?>,
    val localSlotMutables: BooleanArray,
    val localSlotDepths: IntArray,
    val constants: List<BytecodeConst>,
    val fallbackStatements: List<net.sergeych.lyng.Statement>,
    val cmds: Array<Cmd>,
) {
    init {
        require(scopeSlotDepths.size == scopeSlotCount) { "scopeSlotDepths size mismatch" }
        require(scopeSlotIndices.size == scopeSlotCount) { "scopeSlotIndices size mismatch" }
        require(scopeSlotNames.size == scopeSlotCount) { "scopeSlotNames size mismatch" }
        require(localSlotNames.size == localSlotMutables.size) { "localSlot metadata size mismatch" }
        require(localSlotNames.size == localSlotDepths.size) { "localSlot depth metadata size mismatch" }
        require(localSlotNames.size <= localCount) { "localSlotNames exceed localCount" }
        require(addrCount >= 0) { "addrCount must be non-negative" }
    }
}
