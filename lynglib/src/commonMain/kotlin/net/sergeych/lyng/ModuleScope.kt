/*
 * Copyright 2025 Sergey S. Chernov real.sergeych@gmail.com
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

import net.sergeych.lyng.obj.ObjRecord
import net.sergeych.lyng.obj.ObjString
import net.sergeych.lyng.pacman.ImportProvider

/**
 * Module scope supports importing and contains the [importProvider]; it should be the same
 * used in [Compiler];
 */
class ModuleScope(
    var importProvider: ImportProvider,
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
                val existing = scope.objects[newName]
                if (existing != null ) {
                    if (existing.importedFrom != record.importedFrom)
                        scope.raiseError("symbol ${existing.importedFrom?.packageName}.$newName already exists, redefinition on import is not allowed")
                    // already imported
                }
                else {
                    // when importing records, we keep track of its package (not otherwise needed)
                    if (record.importedFrom == null) record.importedFrom = this
                    scope.objects[newName] = record
                }
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


