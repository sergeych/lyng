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
    val scopeSlotRequiresPreparedBinding: BooleanArray,
    val scopeSlotRefPos: Array<net.sergeych.lyng.Pos?>,
    val localSlotNames: Array<String?>,
    val localSlotMutables: BooleanArray,
    val localSlotDelegated: BooleanArray,
    val localSlotCaptures: BooleanArray,
    val constants: List<BytecodeConst>,
    val cmds: Array<Cmd>,
    val posByIp: Array<net.sergeych.lyng.Pos?>,
    val fastOnly: Boolean = false,
) {
    init {
        require(scopeSlotIndices.size == scopeSlotCount) { "scopeSlotIndices size mismatch" }
        require(scopeSlotNames.size == scopeSlotCount) { "scopeSlotNames size mismatch" }
        require(scopeSlotIsModule.size == scopeSlotCount) { "scopeSlotIsModule size mismatch" }
        require(scopeSlotRequiresPreparedBinding.size == scopeSlotCount) { "scopeSlotRequiresPreparedBinding size mismatch" }
        require(scopeSlotRefPos.size == scopeSlotCount) { "scopeSlotRefPos size mismatch" }
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

internal fun computeFastOnlyBytecode(scopeSlotCount: Int, cmds: Array<Cmd>): Boolean {
    if (scopeSlotCount != 0) return false
    return cmds.all(::supportsFastOnlyExecution)
}

private fun supportsFastOnlyExecution(cmd: Cmd): Boolean {
    return when (cmd) {
        is CmdMoveIntLocal,
        is CmdMoveRealLocal,
        is CmdMoveBoolLocal,
        is CmdConstObj,
        is CmdConstInt,
        is CmdConstIntLocal,
        is CmdConstReal,
        is CmdConstBool,
        is CmdConstNull,
        is CmdUnboxIntObjLocal,
        is CmdUnboxRealObjLocal,
        is CmdIntToRealLocal,
        is CmdRealToIntLocal,
        is CmdBoolToIntLocal,
        is CmdIntToBoolLocal,
        is CmdAddIntLocal,
        is CmdSubIntLocal,
        is CmdMulIntLocal,
        is CmdDivIntLocal,
        is CmdModIntLocal,
        is CmdNegIntLocal,
        is CmdIncIntLocal,
        is CmdDecIntLocal,
        is CmdAddRealLocal,
        is CmdSubRealLocal,
        is CmdMulRealLocal,
        is CmdDivRealLocal,
        is CmdNegRealLocal,
        is CmdAndIntLocal,
        is CmdOrIntLocal,
        is CmdXorIntLocal,
        is CmdShlIntLocal,
        is CmdShrIntLocal,
        is CmdUshrIntLocal,
        is CmdInvIntLocal,
        is CmdCmpEqIntLocal,
        is CmdCmpNeqIntLocal,
        is CmdCmpLtIntLocal,
        is CmdCmpLteIntLocal,
        is CmdCmpGtIntLocal,
        is CmdCmpGteIntLocal,
        is CmdCmpEqRealLocal,
        is CmdCmpNeqRealLocal,
        is CmdCmpLtRealLocal,
        is CmdCmpLteRealLocal,
        is CmdCmpGtRealLocal,
        is CmdCmpGteRealLocal,
        is CmdCmpEqBoolLocal,
        is CmdCmpNeqBoolLocal,
        is CmdCmpEqIntRealLocal,
        is CmdCmpEqRealIntLocal,
        is CmdCmpLtIntRealLocal,
        is CmdCmpLtRealIntLocal,
        is CmdCmpLteIntRealLocal,
        is CmdCmpLteRealIntLocal,
        is CmdCmpGtIntRealLocal,
        is CmdCmpGtRealIntLocal,
        is CmdCmpGteIntRealLocal,
        is CmdCmpGteRealIntLocal,
        is CmdCmpNeqIntRealLocal,
        is CmdCmpNeqRealIntLocal,
        is CmdCmpEqStrLocal,
        is CmdCmpNeqStrLocal,
        is CmdCmpLtStrLocal,
        is CmdCmpLteStrLocal,
        is CmdCmpGtStrLocal,
        is CmdCmpGteStrLocal,
        is CmdCmpEqIntObjLocal,
        is CmdCmpNeqIntObjLocal,
        is CmdCmpLtIntObjLocal,
        is CmdCmpLteIntObjLocal,
        is CmdCmpGtIntObjLocal,
        is CmdCmpGteIntObjLocal,
        is CmdCmpEqRealObjLocal,
        is CmdCmpNeqRealObjLocal,
        is CmdCmpLtRealObjLocal,
        is CmdCmpLteRealObjLocal,
        is CmdCmpGtRealObjLocal,
        is CmdCmpGteRealObjLocal,
        is CmdAddIntObjLocal,
        is CmdSubIntObjLocal,
        is CmdMulIntObjLocal,
        is CmdDivIntObjLocal,
        is CmdModIntObjLocal,
        is CmdAddRealObjLocal,
        is CmdSubRealObjLocal,
        is CmdMulRealObjLocal,
        is CmdDivRealObjLocal,
        is CmdModRealObjLocal,
        is CmdNotBoolLocal,
        is CmdAndBoolLocal,
        is CmdOrBoolLocal,
        is CmdJmp,
        is CmdJmpIfTrueLocal,
        is CmdJmpIfFalseLocal,
        is CmdJmpIfEqIntLocal,
        is CmdJmpIfNeqIntLocal,
        is CmdJmpIfLtIntLocal,
        is CmdJmpIfLteIntLocal,
        is CmdJmpIfGtIntLocal,
        is CmdJmpIfGteIntLocal,
        is CmdRet,
        is CmdRetVoid -> true

        else -> false
    }
}
