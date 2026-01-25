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
import net.sergeych.lyng.obj.LocalSlotRef
import net.sergeych.lyng.obj.ObjFalse
import net.sergeych.lyng.obj.ObjInt
import net.sergeych.lyng.obj.ObjTrue
import net.sergeych.lyng.obj.ObjReal
import net.sergeych.lyng.obj.ObjString
import net.sergeych.lyng.obj.ObjList
import net.sergeych.lyng.obj.AssignRef
import net.sergeych.lyng.obj.ValueFnRef
import net.sergeych.lyng.obj.ObjVoid
import net.sergeych.lyng.obj.toBool
import net.sergeych.lyng.obj.toDouble
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

    @Test
    fun ifWithoutElseReturnsVoid() = kotlinx.coroutines.test.runTest {
        val cond = ExpressionStatement(
            BinaryOpRef(
                BinOp.LT,
                ConstRef(ObjInt.of(2).asReadonly),
                ConstRef(ObjInt.of(1).asReadonly),
            ),
            net.sergeych.lyng.Pos.builtIn
        )
        val thenStmt = ExpressionStatement(
            ConstRef(ObjInt.of(10).asReadonly),
            net.sergeych.lyng.Pos.builtIn
        )
        val ifStmt = IfStatement(cond, thenStmt, null, net.sergeych.lyng.Pos.builtIn)
        val fn = BytecodeCompiler().compileStatement("ifNoElse", ifStmt).also {
            if (it == null) {
                error("bytecode compile failed for ifNoElse")
            }
        }!!
        val result = BytecodeVm().execute(fn, Scope(), emptyList())
        assertEquals(ObjVoid, result)
    }

    @Test
    fun andIsShortCircuit() = kotlinx.coroutines.test.runTest {
        val throwingRef = ValueFnRef { error("should not execute") }
        val expr = ExpressionStatement(
            BinaryOpRef(
                BinOp.AND,
                ConstRef(ObjFalse.asReadonly),
                throwingRef
            ),
            net.sergeych.lyng.Pos.builtIn
        )
        val fn = BytecodeCompiler().compileExpression("andShort", expr) ?: error("bytecode compile failed")
        val result = BytecodeVm().execute(fn, Scope(), emptyList())
        assertEquals(false, result.toBool())
    }

    @Test
    fun orIsShortCircuit() = kotlinx.coroutines.test.runTest {
        val throwingRef = ValueFnRef { error("should not execute") }
        val expr = ExpressionStatement(
            BinaryOpRef(
                BinOp.OR,
                ConstRef(ObjTrue.asReadonly),
                throwingRef
            ),
            net.sergeych.lyng.Pos.builtIn
        )
        val fn = BytecodeCompiler().compileExpression("orShort", expr) ?: error("bytecode compile failed")
        val result = BytecodeVm().execute(fn, Scope(), emptyList())
        assertEquals(true, result.toBool())
    }

    @Test
    fun realArithmeticUsesBytecodeOps() = kotlinx.coroutines.test.runTest {
        val expr = ExpressionStatement(
            BinaryOpRef(
                BinOp.PLUS,
                ConstRef(ObjReal.of(2.5).asReadonly),
                ConstRef(ObjReal.of(3.25).asReadonly),
            ),
            net.sergeych.lyng.Pos.builtIn
        )
        val fn = BytecodeCompiler().compileExpression("realPlus", expr) ?: error("bytecode compile failed")
        val result = BytecodeVm().execute(fn, Scope(), emptyList())
        assertEquals(5.75, result.toDouble())
    }

    @Test
    fun mixedIntRealComparisonUsesBytecodeOps() = kotlinx.coroutines.test.runTest {
        val ltExpr = ExpressionStatement(
            BinaryOpRef(
                BinOp.LT,
                ConstRef(ObjInt.of(2).asReadonly),
                ConstRef(ObjReal.of(2.5).asReadonly),
            ),
            net.sergeych.lyng.Pos.builtIn
        )
        val ltFn = BytecodeCompiler().compileExpression("mixedLt", ltExpr) ?: error("bytecode compile failed")
        val ltResult = BytecodeVm().execute(ltFn, Scope(), emptyList())
        assertEquals(true, ltResult.toBool())

        val eqExpr = ExpressionStatement(
            BinaryOpRef(
                BinOp.EQ,
                ConstRef(ObjReal.of(4.0).asReadonly),
                ConstRef(ObjInt.of(4).asReadonly),
            ),
            net.sergeych.lyng.Pos.builtIn
        )
        val eqFn = BytecodeCompiler().compileExpression("mixedEq", eqExpr) ?: error("bytecode compile failed")
        val eqResult = BytecodeVm().execute(eqFn, Scope(), emptyList())
        assertEquals(true, eqResult.toBool())
    }

    @Test
    fun mixedIntRealArithmeticUsesBytecodeOps() = kotlinx.coroutines.test.runTest {
        val expr = ExpressionStatement(
            BinaryOpRef(
                BinOp.PLUS,
                ConstRef(ObjInt.of(2).asReadonly),
                ConstRef(ObjReal.of(3.5).asReadonly),
            ),
            net.sergeych.lyng.Pos.builtIn
        )
        val fn = BytecodeCompiler().compileExpression("mixedPlus", expr) ?: error("bytecode compile failed")
        val result = BytecodeVm().execute(fn, Scope(), emptyList())
        assertEquals(5.5, result.toDouble())
    }

    @Test
    fun mixedIntRealNotEqualUsesBytecodeOps() = kotlinx.coroutines.test.runTest {
        val expr = ExpressionStatement(
            BinaryOpRef(
                BinOp.NEQ,
                ConstRef(ObjInt.of(3).asReadonly),
                ConstRef(ObjReal.of(2.5).asReadonly),
            ),
            net.sergeych.lyng.Pos.builtIn
        )
        val fn = BytecodeCompiler().compileExpression("mixedNeq", expr) ?: error("bytecode compile failed")
        val result = BytecodeVm().execute(fn, Scope(), emptyList())
        assertEquals(true, result.toBool())
    }

    @Test
    fun localSlotTypeTrackingEnablesArithmetic() = kotlinx.coroutines.test.runTest {
        val slotRef = LocalSlotRef("a", 0, 0, true, false, net.sergeych.lyng.Pos.builtIn)
        val assign = AssignRef(
            slotRef,
            ConstRef(ObjInt.of(2).asReadonly),
            net.sergeych.lyng.Pos.builtIn
        )
        val expr = ExpressionStatement(
            BinaryOpRef(
                BinOp.PLUS,
                assign,
                slotRef
            ),
            net.sergeych.lyng.Pos.builtIn
        )
        val fn = BytecodeCompiler().compileExpression("localSlotAdd", expr) ?: error("bytecode compile failed")
        val scope = Scope().apply { applySlotPlan(mapOf("a" to 0)) }
        val result = BytecodeVm().execute(fn, scope, emptyList())
        assertEquals(4, result.toInt())
    }

    @Test
    fun parentScopeSlotAccessWorks() = kotlinx.coroutines.test.runTest {
        val parentRef = LocalSlotRef("a", 0, 1, true, false, net.sergeych.lyng.Pos.builtIn)
        val expr = ExpressionStatement(
            BinaryOpRef(
                BinOp.PLUS,
                parentRef,
                ConstRef(ObjInt.of(2).asReadonly)
            ),
            net.sergeych.lyng.Pos.builtIn
        )
        val fn = BytecodeCompiler().compileExpression("parentSlotAdd", expr) ?: error("bytecode compile failed")
        val parent = Scope().apply {
            applySlotPlan(mapOf("a" to 0))
            setSlotValue(0, ObjInt.of(3))
        }
        val child = Scope(parent)
        val result = BytecodeVm().execute(fn, child, emptyList())
        assertEquals(5, result.toInt())
    }

    @Test
    fun objectEqualityUsesBytecodeOps() = kotlinx.coroutines.test.runTest {
        val expr = ExpressionStatement(
            BinaryOpRef(
                BinOp.EQ,
                ConstRef(ObjString("abc").asReadonly),
                ConstRef(ObjString("abc").asReadonly),
            ),
            net.sergeych.lyng.Pos.builtIn
        )
        val fn = BytecodeCompiler().compileExpression("objEq", expr) ?: error("bytecode compile failed")
        val result = BytecodeVm().execute(fn, Scope(), emptyList())
        assertEquals(true, result.toBool())
    }

    @Test
    fun objectReferenceEqualityUsesBytecodeOps() = kotlinx.coroutines.test.runTest {
        val shared = ObjList()
        val eqExpr = ExpressionStatement(
            BinaryOpRef(
                BinOp.REF_EQ,
                ConstRef(shared.asReadonly),
                ConstRef(shared.asReadonly),
            ),
            net.sergeych.lyng.Pos.builtIn
        )
        val eqFn = BytecodeCompiler().compileExpression("objRefEq", eqExpr) ?: error("bytecode compile failed")
        val eqResult = BytecodeVm().execute(eqFn, Scope(), emptyList())
        assertEquals(true, eqResult.toBool())

        val neqExpr = ExpressionStatement(
            BinaryOpRef(
                BinOp.REF_NEQ,
                ConstRef(ObjList().asReadonly),
                ConstRef(ObjList().asReadonly),
            ),
            net.sergeych.lyng.Pos.builtIn
        )
        val neqFn = BytecodeCompiler().compileExpression("objRefNeq", neqExpr) ?: error("bytecode compile failed")
        val neqResult = BytecodeVm().execute(neqFn, Scope(), emptyList())
        assertEquals(true, neqResult.toBool())
    }

    @Test
    fun objectComparisonUsesBytecodeOps() = kotlinx.coroutines.test.runTest {
        val ltExpr = ExpressionStatement(
            BinaryOpRef(
                BinOp.LT,
                ConstRef(ObjString("a").asReadonly),
                ConstRef(ObjString("b").asReadonly),
            ),
            net.sergeych.lyng.Pos.builtIn
        )
        val ltFn = BytecodeCompiler().compileExpression("objLt", ltExpr) ?: error("bytecode compile failed")
        val ltResult = BytecodeVm().execute(ltFn, Scope(), emptyList())
        assertEquals(true, ltResult.toBool())

        val gteExpr = ExpressionStatement(
            BinaryOpRef(
                BinOp.GTE,
                ConstRef(ObjString("b").asReadonly),
                ConstRef(ObjString("a").asReadonly),
            ),
            net.sergeych.lyng.Pos.builtIn
        )
        val gteFn = BytecodeCompiler().compileExpression("objGte", gteExpr) ?: error("bytecode compile failed")
        val gteResult = BytecodeVm().execute(gteFn, Scope(), emptyList())
        assertEquals(true, gteResult.toBool())
    }

    @Test
    fun objectArithmeticUsesBytecodeOps() = kotlinx.coroutines.test.runTest {
        val expr = ExpressionStatement(
            BinaryOpRef(
                BinOp.PLUS,
                ConstRef(ObjString("a").asReadonly),
                ConstRef(ObjString("b").asReadonly),
            ),
            net.sergeych.lyng.Pos.builtIn
        )
        val fn = BytecodeCompiler().compileExpression("objPlus", expr) ?: error("bytecode compile failed")
        val result = BytecodeVm().execute(fn, Scope(), emptyList())
        assertEquals("ab", (result as ObjString).value)
    }
}
