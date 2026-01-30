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
 *
 */

package net.sergeych.lyng.resolution

import net.sergeych.lyng.Compiler
import net.sergeych.lyng.Pos
import net.sergeych.lyng.Source
import net.sergeych.lyng.pacman.ImportProvider

enum class SymbolOrigin {
    LOCAL,
    OUTER,
    MODULE,
    MEMBER,
    PARAM
}

data class ResolvedSymbol(
    val name: String,
    val origin: SymbolOrigin,
    val slotIndex: Int,
    val pos: Pos,
)

data class CaptureInfo(
    val name: String,
    val origin: SymbolOrigin,
    val slotIndex: Int,
    val isMutable: Boolean,
    val pos: Pos,
)

data class ResolutionError(
    val message: String,
    val pos: Pos,
)

data class ResolutionWarning(
    val message: String,
    val pos: Pos,
)

data class ResolutionReport(
    val moduleName: String,
    val symbols: List<ResolvedSymbol>,
    val captures: List<CaptureInfo>,
    val errors: List<ResolutionError>,
    val warnings: List<ResolutionWarning>,
)

object CompileTimeResolver {
    suspend fun dryRun(source: Source, importProvider: ImportProvider): ResolutionReport {
        val collector = ResolutionCollector(source.fileName)
        Compiler.compileWithResolution(
            source,
            importProvider,
            resolutionSink = collector,
            useBytecodeStatements = false,
            allowUnresolvedRefs = true
        )
        return collector.buildReport()
    }
}
