package net.sergeych.lyng

/**
 * List of argument declarations in the __definition__ of the lambda, class constructor,
 * function, etc. It is created by [Compiler.parseArgsDeclaration]
 */
data class ArgsDeclaration(val params: List<Item>, val endTokenType: Token.Type) {
    init {
        val i = params.count { it.isEllipsis }
        if (i > 1) throw ScriptError(params[i].pos, "there can be only one argument")
        val start = params.indexOfFirst { it.defaultValue != null }
        if (start >= 0)
            for (j in start + 1 until params.size)
                if (params[j].defaultValue == null) throw ScriptError(
                    params[j].pos,
                    "required argument can't follow default one"
                )
    }

    /**
     * parse args and create local vars in a given context
     */
    suspend fun assignToContext(
        context: Context,
        fromArgs: Arguments = context.args,
        defaultAccessType: Compiler.AccessType = Compiler.AccessType.Var
    ) {
        fun assign(a: Item, value: Obj) {
            context.addItem(a.name, (a.accessType ?: defaultAccessType).isMutable, value)
        }

        suspend fun processHead(index: Int): Int {
            var i = index
            while (i != params.size) {
                val a = params[i]
                if (a.isEllipsis) break
                val value = when {
                    i < fromArgs.size -> fromArgs[i]
                    a.defaultValue != null -> a.defaultValue.execute(context)
                    else -> context.raiseArgumentError("too few arguments for the call")
                }
                assign(a, value)
                i++
            }
            return i
        }

        suspend fun processTail(index: Int): Int {
            var i = params.size - 1
            var j = fromArgs.size - 1
            while (i > index) {
                val a = params[i]
                if (a.isEllipsis) break
                val value = when {
                    j >= index -> {
                        fromArgs[j--]
                    }

                    a.defaultValue != null -> a.defaultValue.execute(context)
                    else -> context.raiseArgumentError("too few arguments for the call")
                }
                assign(a, value)
                i--
            }
            return j
        }

        fun processEllipsis(index: Int, toFromIndex: Int) {
            val a = params[index]
            val l = if (index > toFromIndex) ObjList()
            else ObjList(fromArgs.values.subList(index, toFromIndex + 1).toMutableList())
            assign(a, l)
        }

        val leftIndex = processHead(0)
        if (leftIndex < params.size) {
            val end = processTail(leftIndex)
            processEllipsis(leftIndex, end)
        } else {
            if (leftIndex < fromArgs.size)
                context.raiseArgumentError("too many arguments for the call")
        }
    }

    /**
     * Single argument declaration descriptor.
     *
     * @param defaultValue default value, if set, can't be an [Obj] as it can depend on the call site, call args, etc.
     *      If not null, could be executed on __caller context__ only.
     */
    data class Item(
        val name: String,
        val type: TypeDecl = TypeDecl.Obj,
        val pos: Pos = Pos.builtIn,
        val isEllipsis: Boolean = false,
        /**
         * Default value, if set, can't be an [Obj] as it can depend on the call site, call args, etc.
         * So it is a [Statement] that must be executed on __caller context__.
         */
        val defaultValue: Statement? = null,
        val accessType: Compiler.AccessType? = null,
        val visibility: Compiler.Visibility? = null,
    )
}