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

import net.sergeych.lyng.ExpressionStatement
import net.sergeych.lyng.IfStatement
import net.sergeych.lyng.Scope
import net.sergeych.lyng.bytecode.BytecodeBuilder
import net.sergeych.lyng.bytecode.BytecodeCompiler
import net.sergeych.lyng.bytecode.BytecodeConst
import net.sergeych.lyng.bytecode.BytecodeVm
import net.sergeych.lyng.bytecode.Opcode
import net.sergeych.lyng.obj.BinaryOpRef
import net.sergeych.lyng.obj.BinOp
import net.sergeych.lyng.obj.ConstRef
import net.sergeych.lyng.obj.ObjInt
import net.sergeych.lyng.obj.toInt
import kotlin.test.Test
import kotlin.test.assertEquals

class BytecodeVmTest {
    @Test
    fun addsIntConstants() = kotlinx.coroutines.test.runTest {
        val builder = BytecodeBuilder()
        val k0 = builder.addConst(BytecodeConst.IntVal(2))
        val k1 = builder.addConst(BytecodeConst.IntVal(3))
        builder.emit(Opcode.CONST_INT, k0, 0)
        builder.emit(Opcode.CONST_INT, k1, 1)
        builder.emit(Opcode.ADD_INT, 0, 1, 2)
        builder.emit(Opcode.RET, 2)
        val fn = builder.build("addInts", localCount = 3)
        val result = BytecodeVm().execute(fn, Scope(), emptyList())
        assertEquals(5, result.toInt())
    }

    @Test
    fun ifExpressionReturnsThenValue() = kotlinx.coroutines.test.runTest {
        val cond = ExpressionStatement(
            BinaryOpRef(
                BinOp.LT,
                ConstRef(ObjInt.of(2).asReadonly),
                ConstRef(ObjInt.of(3).asReadonly),
            ),
            net.sergeych.lyng.Pos.builtIn
        )
        val thenStmt = ExpressionStatement(
            ConstRef(ObjInt.of(10).asReadonly),
            net.sergeych.lyng.Pos.builtIn
        )
        val elseStmt = ExpressionStatement(
            ConstRef(ObjInt.of(20).asReadonly),
            net.sergeych.lyng.Pos.builtIn
        )
        val ifStmt = IfStatement(cond, thenStmt, elseStmt, net.sergeych.lyng.Pos.builtIn)
        val fn = BytecodeCompiler().compileStatement("ifTest", ifStmt) ?: error("bytecode compile failed")
        val result = BytecodeVm().execute(fn, Scope(), emptyList())
        assertEquals(10, result.toInt())
    }
}
