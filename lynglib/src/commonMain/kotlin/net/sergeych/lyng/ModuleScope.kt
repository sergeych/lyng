package net.sergeych.lyng

/**
 * Module scope supports importing and contains the [pacman]; it should be the same
 * used in [Compiler];
 */
class ModuleScope(
    val pacman: Pacman,
    pos: Pos = Pos.builtIn,
    val packageName: String
) : Scope(pacman.rootScope, Arguments.EMPTY, pos) {

    constructor(pacman: Pacman,source: Source) : this(pacman, source.startPos, source.fileName)

    override suspend fun checkImport(pos: Pos, name: String, symbols: Map<String, String>?) {
        pacman.prepareImport(pos, name, symbols)
    }

    /**
     * Import symbols into the scope. It _is called_ after the module is imported by [checkImport].
     * If [checkImport] was not called, the symbols will not be imported with exception as module is not found.
     */
    override suspend fun importInto(scope: Scope, name: String, symbols: Map<String, String>?) {
        pacman.performImport(scope, name, symbols)
    }

    val packageNameObj by lazy { ObjString(packageName).asReadonly}

    override fun get(name: String): ObjRecord? {
        return if( name == "__PACKAGE__")
            packageNameObj
        else
            super.get(name)
    }
}


