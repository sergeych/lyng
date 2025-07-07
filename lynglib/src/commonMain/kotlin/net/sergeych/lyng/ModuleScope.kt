package net.sergeych.lyng

import net.sergeych.lyng.pacman.ImportProvider

/**
 * Module scope supports importing and contains the [importProvider]; it should be the same
 * used in [Compiler];
 */
class ModuleScope(
    val importProvider: ImportProvider,
    pos: Pos = Pos.builtIn,
    override val packageName: String
) : Scope(importProvider.rootScope, Arguments.EMPTY, pos) {

    constructor(importProvider: ImportProvider, source: Source) : this(importProvider, source.startPos, source.fileName)

    /**
     * Import symbols into the scope. It _is called_ after the module is imported by [ImportProvider.prepareImport]
     * which checks symbol availability and accessibility prior to execution.
     * @param scope where to copy symbols from this module
     * @param symbols symbols to import, ir present, only symbols keys will be imported renamed to corresponding values
     */
    override suspend fun importInto(scope: Scope, symbols: Map<String, String>?) {
        val symbolsToImport = symbols?.keys?.toMutableSet()
        for ((symbol, record) in this.objects) {
            if (record.visibility.isPublic) {
                val newName = symbols?.let { ss: Map<String, String> ->
                    ss[symbol]
                        ?.also { symbolsToImport!!.remove(it) }
                        ?: scope.raiseError("internal error: symbol $symbol not found though the module is cached")
                } ?: symbol
                if (newName in scope.objects)
                    scope.raiseError("symbol $newName already exists, redefinition on import is not allowed")
                scope.objects[newName] = record
            }
        }
        if (!symbolsToImport.isNullOrEmpty())
            scope.raiseSymbolNotFound("symbols $packageName.{$symbolsToImport} are.were not found")
    }

    val packageNameObj by lazy { ObjString(packageName).asReadonly }

    override fun get(name: String): ObjRecord? {
        return if (name == "__PACKAGE__")
            packageNameObj
        else
            super.get(name)
    }
}


