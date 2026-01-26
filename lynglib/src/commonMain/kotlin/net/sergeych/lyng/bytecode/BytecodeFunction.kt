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

data class BytecodeFunction(
    val name: String,
    val localCount: Int,
    val scopeSlotCount: Int,
    val scopeSlotDepths: IntArray,
    val scopeSlotIndices: IntArray,
    val scopeSlotNames: Array<String?>,
    val slotWidth: Int,
    val ipWidth: Int,
    val constIdWidth: Int,
    val constants: List<BytecodeConst>,
    val fallbackStatements: List<net.sergeych.lyng.Statement>,
    val code: ByteArray,
) {
    val methodCallSites: MutableMap<Int, MethodCallSite> = mutableMapOf()

    init {
        require(slotWidth == 1 || slotWidth == 2 || slotWidth == 4) { "slotWidth must be 1,2,4" }
        require(ipWidth == 2 || ipWidth == 4) { "ipWidth must be 2 or 4" }
        require(constIdWidth == 2 || constIdWidth == 4) { "constIdWidth must be 2 or 4" }
        require(scopeSlotDepths.size == scopeSlotCount) { "scopeSlotDepths size mismatch" }
        require(scopeSlotIndices.size == scopeSlotCount) { "scopeSlotIndices size mismatch" }
        require(scopeSlotNames.size == scopeSlotCount) { "scopeSlotNames size mismatch" }
    }
}
