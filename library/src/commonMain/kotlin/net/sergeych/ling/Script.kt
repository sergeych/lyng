package net.sergeych.ling

class Script(
    override val pos: Pos,
    private val statements: List<Statement> = emptyList(),
) : Statement() {

    override suspend fun execute(context: Context): Obj {
        // todo: run script
        var lastResult: Obj = Void
        for (s in statements) {
            lastResult = s.execute(context)
        }
        return lastResult
    }

    suspend fun execute() = execute(defaultContext)

    companion object {
        val defaultContext: Context = Context(Pos.builtIn)
    }
}