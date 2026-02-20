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

import net.sergeych.lyng.bytecode.BytecodeFrame
import net.sergeych.lyng.bytecode.CmdFunction
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

    internal var importedModules: List<ModuleScope> = emptyList()
    internal var moduleFrame: BytecodeFrame? = null
    internal var moduleFrameLocalCount: Int = -1
    internal var moduleFrameLocalSlotNames: Array<String?> = emptyArray()
    internal var moduleFrameLocalSlotMutables: BooleanArray = BooleanArray(0)
    internal var moduleFrameLocalSlotDelegated: BooleanArray = BooleanArray(0)

    internal fun ensureModuleFrame(fn: CmdFunction): BytecodeFrame {
        val current = moduleFrame
        val frame = if (current == null) {
            BytecodeFrame(fn.localCount, 0).also {
                moduleFrame = it
                moduleFrameLocalCount = fn.localCount
            }
        } else if (fn.localCount > moduleFrameLocalCount) {
            val next = BytecodeFrame(fn.localCount, 0)
            current.copyTo(next)
            moduleFrame = next
            moduleFrameLocalCount = fn.localCount
            // Retarget frame-based locals to the new frame instance.
            val localNames = fn.localSlotNames
            for (i in localNames.indices) {
                val name = localNames[i] ?: continue
                val record = objects[name] ?: localBindings[name] ?: continue
                val value = record.value
                if (value is FrameSlotRef && value.refersTo(current, i)) {
                    record.value = FrameSlotRef(next, i)
                    updateSlotFor(name, record)
                }
            }
            next
        } else {
            current
        }
        moduleFrameLocalSlotNames = fn.localSlotNames
        moduleFrameLocalSlotMutables = fn.localSlotMutables
        moduleFrameLocalSlotDelegated = fn.localSlotDelegated
        return frame
    }

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
                        ?.also { symbolsToImport!!.remove(symbol) }
                        ?: return@let null
                } ?: if (symbols == null) symbol else null

                if (newName != null) {
                    val existing = scope.objects[newName]
                    if (existing != null) {
                        if (existing.importedFrom != record.importedFrom)
                            scope.raiseError("symbol ${existing.importedFrom?.packageName}.$newName already exists, redefinition on import is not allowed")
                        // already imported
                    } else {
                        // when importing records, we keep track of its package (not otherwise needed)
                        if (record.importedFrom == null) record.importedFrom = this
                        scope.objects[newName] = record
                        scope.updateSlotFor(newName, record)
                    }
                }
            }
        }
        for ((cls, map) in this.extensions) {
            for ((symbol, record) in map) {
                if (record.visibility.isPublic) {
                    val newName = symbols?.let { ss: Map<String, String> ->
                        ss[symbol]
                            ?.also { symbolsToImport!!.remove(symbol) }
                            ?: return@let null
                    } ?: if (symbols == null) symbol else null

                    if (newName != null) {
                        scope.addExtension(cls, newName, record)
                    }
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
