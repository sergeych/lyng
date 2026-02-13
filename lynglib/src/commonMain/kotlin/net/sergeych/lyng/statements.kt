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

import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjClass

fun String.toSource(name: String = "eval"): Source = Source(name, this)

sealed class ObjType {
    object Any : ObjType()
    object Void: ObjType()

    companion object {
    }
}


@Suppress("unused")
abstract class Statement(
    val isStaticConst: Boolean = false,
    override val isConst: Boolean = false,
    val returnType: ObjType = ObjType.Any
) : Obj() {

    override val objClass: ObjClass = type

    abstract val pos: Pos
    abstract suspend fun execute(scope: Scope): Obj

    override suspend fun compareTo(scope: Scope, other: Obj): Int {
        if( other === this ) return 0
        return -3
    }

    override suspend fun callOn(scope: Scope): Obj {
        return execute(scope)
    }

    override fun toString(): String = "Callable@${this.hashCode()}"

    companion object {
        val type = ObjClass("Callable")
    }

    suspend fun call(scope: Scope, vararg args: Obj) = execute(scope.createChildScope(args =  Arguments(*args)))

    protected fun bytecodeOnly(scope: Scope, label: String): Nothing {
        return scope.raiseIllegalState("bytecode-only execution is required; $label needs compiled bytecode")
    }

}

class IfStatement(
    val condition: Statement,
    val ifBody: Statement,
    val elseBody: Statement?,
    override val pos: Pos,
) : Statement() {
    override suspend fun execute(scope: Scope): Obj {
        return bytecodeOnly(scope, "if statement")
    }
}

data class ConstIntRange(val start: Long, val endExclusive: Long)

class ForInStatement(
    val loopVarName: String,
    val source: Statement,
    val constRange: ConstIntRange?,
    val body: Statement,
    val elseStatement: Statement?,
    val label: String?,
    val canBreak: Boolean,
    val loopSlotPlan: Map<String, Int>,
    val loopScopeId: Int,
    override val pos: Pos,
) : Statement() {
    override suspend fun execute(scope: Scope): Obj {
        return bytecodeOnly(scope, "for-in statement")
    }
}

class WhileStatement(
    val condition: Statement,
    val body: Statement,
    val elseStatement: Statement?,
    val label: String?,
    val canBreak: Boolean,
    val loopSlotPlan: Map<String, Int>,
    override val pos: Pos,
) : Statement() {
    override suspend fun execute(scope: Scope): Obj {
        return bytecodeOnly(scope, "while statement")
    }
}

class DoWhileStatement(
    val body: Statement,
    val condition: Statement,
    val elseStatement: Statement?,
    val label: String?,
    val loopSlotPlan: Map<String, Int>,
    override val pos: Pos,
) : Statement() {
    override suspend fun execute(scope: Scope): Obj {
        return bytecodeOnly(scope, "do-while statement")
    }
}

class BreakStatement(
    val label: String?,
    val resultExpr: Statement?,
    override val pos: Pos,
) : Statement() {
    override suspend fun execute(scope: Scope): Obj {
        return bytecodeOnly(scope, "break statement")
    }
}

class ContinueStatement(
    val label: String?,
    override val pos: Pos,
) : Statement() {
    override suspend fun execute(scope: Scope): Obj {
        return bytecodeOnly(scope, "continue statement")
    }
}

class ReturnStatement(
    val label: String?,
    val resultExpr: Statement?,
    override val pos: Pos,
) : Statement() {
    override suspend fun execute(scope: Scope): Obj {
        return bytecodeOnly(scope, "return statement")
    }
}

class ThrowStatement(
    val throwExpr: Statement,
    override val pos: Pos,
) : Statement() {
    override suspend fun execute(scope: Scope): Obj {
        return bytecodeOnly(scope, "throw statement")
    }
}

class ExpressionStatement(
    val ref: net.sergeych.lyng.obj.ObjRef,
    override val pos: Pos
) : Statement() {
    override suspend fun execute(scope: Scope): Obj = bytecodeOnly(scope, "expression statement")
}

fun Statement.raise(text: String): Nothing {
    throw ScriptError(pos, text)
}

@Suppress("unused")
fun Statement.require(cond: Boolean, message: () -> String) {
    if (!cond) raise(message())
}

object NopStatement: Statement(true, true, ObjType.Void) {
    override val pos: Pos = Pos.builtIn

    override suspend fun execute(scope: Scope): Obj = bytecodeOnly(scope, "nop statement")
}
