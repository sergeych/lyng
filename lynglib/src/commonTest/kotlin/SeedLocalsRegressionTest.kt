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

import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.FrameSlotRef
import net.sergeych.lyng.Pos
import net.sergeych.lyng.Scope
import net.sergeych.lyng.bytecode.*
import kotlin.test.Test
import kotlin.test.assertNull

class SeedLocalsRegressionTest {

    @Test
    fun seedFrameLocalsSkipsSelfReferentialFrameSlotRef() = runTest {
        val fn = CmdFunction(
            name = "seed-self-ref",
            localCount = 1,
            addrCount = 0,
            returnLabels = emptySet(),
            scopeSlotCount = 1,
            scopeSlotIndices = intArrayOf(0),
            scopeSlotNames = arrayOf(null),
            scopeSlotIsModule = booleanArrayOf(false),
            scopeSlotRequiresPreparedBinding = booleanArrayOf(false),
            scopeSlotRefPos = arrayOfNulls(1),
            localSlotNames = arrayOf("x"),
            localSlotMutables = booleanArrayOf(true),
            localSlotDelegated = booleanArrayOf(false),
            localSlotCaptures = booleanArrayOf(false),
            constants = listOf(BytecodeConst.IntVal(0)),
            cmds = emptyArray(),
            posByIp = emptyArray()
        )
        val scope = Scope(null, pos = Pos.builtIn)
        val record = scope.addConst("x", net.sergeych.lyng.obj.ObjInt.of(1))
        val frame = CmdFrame(CmdVm(), fn, scope, emptyList())
        record.value = FrameSlotRef(frame.frame, 0)
        scope.updateSlotFor("x", record)

        seedFrameLocalsFromScope(frame, scope)

        assertNull(frame.frame.getRawObj(0))
    }
}
