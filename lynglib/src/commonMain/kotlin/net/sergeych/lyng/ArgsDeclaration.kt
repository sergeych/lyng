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
                // last non-default could be lambda:
                if (params[j].defaultValue == null && j != params.size - 1) throw ScriptError(
                    params[j].pos,
                    "required argument can't follow default one"
                )
    }

    /**
     * parse args and create local vars in a given context
     */
    suspend fun assignToContext(
        scope: Scope,
        arguments: Arguments = scope.args,
        defaultAccessType: AccessType = AccessType.Var,
        defaultVisibility: Visibility = Visibility.Public
    ) {
        fun assign(a: Item, value: Obj) {
            scope.addItem(a.name, (a.accessType ?: defaultAccessType).isMutable, value,
                a.visibility ?: defaultVisibility)
        }

        // will be used with last lambda arg fix
        val callArgs: List<Obj>
        val paramsSize: Int

        if( arguments.tailBlockMode ) {
            paramsSize = params.size - 1
            assign(params.last(), arguments.list.last())
            callArgs = arguments.list.dropLast(1)
        } else {
            paramsSize = params.size
            callArgs = arguments.list
        }

        suspend fun processHead(index: Int): Int {
            var i = index
            while (i != paramsSize) {
                val a = params[i]
                if (a.isEllipsis) break
                val value = when {
                    i < callArgs.size -> callArgs[i]
                    a.defaultValue != null -> a.defaultValue.execute(scope)
                    else -> {
                        println("callArgs: ${callArgs.joinToString()}")
                        println("tailBlockMode: ${arguments.tailBlockMode}")
                        scope.raiseIllegalArgument("too few arguments for the call")
                    }
                }
                assign(a, value)
                i++
            }
            return i
        }

        suspend fun processTail(index: Int): Int {
            var i = paramsSize - 1
            var j = callArgs.size - 1
            while (i > index) {
                val a = params[i]
                if (a.isEllipsis) break
                val value = when {
                    j >= index -> {
                        callArgs[j--]
                    }

                    a.defaultValue != null -> a.defaultValue.execute(scope)
                    else -> scope.raiseIllegalArgument("too few arguments for the call")
                }
                assign(a, value)
                i--
            }
            return j
        }

        fun processEllipsis(index: Int, toFromIndex: Int) {
            val a = params[index]
            val l = if (index > toFromIndex) ObjList()
            else ObjList(callArgs.subList(index, toFromIndex + 1).toMutableList())
            assign(a, l)
        }

        val leftIndex = processHead(0)
        if (leftIndex < paramsSize) {
            val end = processTail(leftIndex)
            processEllipsis(leftIndex, end)
        } else {
            if (leftIndex < callArgs.size)
                scope.raiseIllegalArgument("too many arguments for the call")
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
        val type: TypeDecl = TypeDecl.TypeAny,
        val pos: Pos = Pos.builtIn,
        val isEllipsis: Boolean = false,
        /**
         * Default value, if set, can't be an [Obj] as it can depend on the call site, call args, etc.
         * So it is a [Statement] that must be executed on __caller context__.
         */
        val defaultValue: Statement? = null,
        val accessType: AccessType? = null,
        val visibility: Visibility? = null,
    )
}