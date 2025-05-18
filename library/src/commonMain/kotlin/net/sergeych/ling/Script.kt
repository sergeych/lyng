package net.sergeych.ling

import kotlin.math.PI
import kotlin.math.sin

class Script(
    override val pos: Pos,
    private val statements: List<Statement> = emptyList(),
) : Statement() {

    override suspend fun execute(context: Context): Obj {
        // todo: run script
        var lastResult: Obj = ObjVoid
        for (s in statements) {
            lastResult = s.execute(context)
        }
        return lastResult
    }

    suspend fun execute() = execute(defaultContext)

    companion object {
        val defaultContext: Context = Context().apply {
            addFn("println") {
                require(args.size == 1)
                println(args[0].asStr.value)
                ObjVoid
            }
            addFn("sin") {
                sin(args.firstAndOnly().toDouble())
            }
            val pi = ObjReal(PI)
            addConst(pi, "π")
            getOrCreateNamespace("Math").also { ns ->
                ns.addConst(pi, "PI")
            }
        }
    }
}