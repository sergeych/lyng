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

package net.sergeych.lyng.idea.util

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import kotlinx.coroutines.runBlocking
import net.sergeych.lyng.binding.BindingSnapshot
import net.sergeych.lyng.miniast.BuiltinDocRegistry
import net.sergeych.lyng.miniast.DocLookupUtils
import net.sergeych.lyng.miniast.MiniEnumDecl
import net.sergeych.lyng.miniast.MiniRange
import net.sergeych.lyng.miniast.MiniScript
import net.sergeych.lyng.tools.IdeLenientImportProvider
import net.sergeych.lyng.tools.LyngAnalysisRequest
import net.sergeych.lyng.tools.LyngAnalysisResult
import net.sergeych.lyng.tools.LyngDiagnostic
import net.sergeych.lyng.tools.LyngLanguageTools
import net.sergeych.lyng.idea.LyngFileType

object LyngAstManager {
    private val MINI_KEY = Key.create<MiniScript>("lyng.mini.cache")
    private val BINDING_KEY = Key.create<BindingSnapshot>("lyng.binding.cache")
    private val STAMP_KEY = Key.create<Long>("lyng.mini.cache.stamp")
    private val ANALYSIS_KEY = Key.create<LyngAnalysisResult>("lyng.analysis.cache")

    fun getMiniAst(file: PsiFile): MiniScript? = runReadAction {
        getAnalysis(file)?.mini
    }

    fun getCombinedStamp(file: PsiFile): Long = runReadAction {
        var combinedStamp = file.viewProvider.modificationStamp
        if (!file.name.endsWith(".lyng.d")) {
            collectDeclarationFiles(file).forEach { df ->
                combinedStamp += df.viewProvider.modificationStamp
            }
        }
        combinedStamp
    }

    private fun collectDeclarationFiles(file: PsiFile): List<PsiFile> = runReadAction {
        val psiManager = PsiManager.getInstance(file.project)
        val seen = mutableSetOf<String>()
        val result = mutableListOf<PsiFile>()

        var currentDir = file.containingDirectory
        while (currentDir != null) {
            for (child in currentDir.files) {
                if (child.name.endsWith(".lyng.d") && child != file && seen.add(child.virtualFile.path)) {
                    result.add(child)
                }
            }
            currentDir = currentDir.parentDirectory
        }

        if (result.isNotEmpty()) return@runReadAction result

        // Fallback for virtual/light files without a stable parent chain (e.g., tests)
        val basePath = file.virtualFile?.path ?: return@runReadAction result
        val scope = GlobalSearchScope.projectScope(file.project)
        val dFiles = FilenameIndex.getAllFilesByExt(file.project, "d", scope)
        for (vFile in dFiles) {
            if (!vFile.name.endsWith(".lyng.d")) continue
            if (vFile.path == basePath) continue
            val parentPath = vFile.parent?.path ?: continue
            if (basePath == parentPath || basePath.startsWith(parentPath.trimEnd('/') + "/")) {
                if (seen.add(vFile.path)) {
                    psiManager.findFile(vFile)?.let { result.add(it) }
                }
            }
        }

        if (result.isNotEmpty()) return@runReadAction result

        // Fallback: scan all Lyng files in project index and filter by .lyng.d
        val lyngFiles = FileTypeIndex.getFiles(LyngFileType, scope)
        for (vFile in lyngFiles) {
            if (!vFile.name.endsWith(".lyng.d")) continue
            if (vFile.path == basePath) continue
            if (seen.add(vFile.path)) {
                psiManager.findFile(vFile)?.let { result.add(it) }
            }
        }

        if (result.isNotEmpty()) return@runReadAction result

        // Final fallback: include all .lyng.d files in project scope
        for (vFile in dFiles) {
            if (!vFile.name.endsWith(".lyng.d")) continue
            if (vFile.path == basePath) continue
            if (seen.add(vFile.path)) {
                psiManager.findFile(vFile)?.let { result.add(it) }
            }
        }
        result
    }

    fun getDeclarationFiles(file: PsiFile): List<PsiFile> = runReadAction {
        collectDeclarationFiles(file)
    }

    fun getBinding(file: PsiFile): BindingSnapshot? = runReadAction {
        getAnalysis(file)?.binding
    }

    fun getAnalysis(file: PsiFile): LyngAnalysisResult? = runReadAction {
        val vFile = file.virtualFile ?: return@runReadAction null
        val combinedStamp = getCombinedStamp(file)
        val prevStamp = file.getUserData(STAMP_KEY)
        val cached = file.getUserData(ANALYSIS_KEY)
        if (cached != null && prevStamp != null && prevStamp == combinedStamp) return@runReadAction cached

        val text = file.viewProvider.contents.toString()
        val built = try {
            val provider = IdeLenientImportProvider.create()
            runBlocking {
                LyngLanguageTools.analyze(
                    LyngAnalysisRequest(text = text, fileName = file.name, importProvider = provider)
                )
            }
        } catch (_: Throwable) {
            null
        }

        if (built != null) {
            val isDecl = file.name.endsWith(".lyng.d")
            val merged = if (!isDecl && built.mini == null) {
                MiniScript(MiniRange(built.source.startPos, built.source.startPos))
            } else {
                built.mini
            }
            if (merged != null && !isDecl) {
                val dFiles = collectDeclarationFiles(file)
                for (df in dFiles) {
                    val dMini = getAnalysis(df)?.mini ?: run {
                        val dText = df.viewProvider.contents.toString()
                        try {
                            val provider = IdeLenientImportProvider.create()
                            runBlocking {
                                LyngLanguageTools.analyze(
                                    LyngAnalysisRequest(text = dText, fileName = df.name, importProvider = provider)
                                )
                            }.mini
                        } catch (_: Throwable) {
                            null
                        }
                    } ?: continue
                    merged.declarations.addAll(dMini.declarations)
                    merged.imports.addAll(dMini.imports)
                }
            }
            val finalAnalysis = if (merged != null) {
                val mergedImports = DocLookupUtils.canonicalImportedModules(merged, text)
                built.copy(
                    mini = merged,
                    importedModules = mergedImports,
                    diagnostics = filterDiagnostics(built.diagnostics, merged, text, mergedImports)
                )
            } else {
                built
            }
            file.putUserData(ANALYSIS_KEY, finalAnalysis)
            file.putUserData(MINI_KEY, finalAnalysis.mini)
            file.putUserData(BINDING_KEY, finalAnalysis.binding)
            file.putUserData(STAMP_KEY, combinedStamp)
            return@runReadAction finalAnalysis
        }
        null
    }

    private fun filterDiagnostics(
        diagnostics: List<LyngDiagnostic>,
        merged: MiniScript,
        text: String,
        importedModules: List<String>
    ): List<LyngDiagnostic> {
        if (diagnostics.isEmpty()) return diagnostics
        val declaredTopLevel = merged.declarations.map { it.name }.toSet()

        val declaredMembers = linkedSetOf<String>()
        val aggregatedClasses = DocLookupUtils.aggregateClasses(importedModules, merged)
        for (cls in aggregatedClasses.values) {
            cls.members.forEach { declaredMembers.add(it.name) }
            cls.ctorFields.forEach { declaredMembers.add(it.name) }
            cls.classFields.forEach { declaredMembers.add(it.name) }
        }
        merged.declarations.filterIsInstance<MiniEnumDecl>().forEach { en ->
            DocLookupUtils.enumToSyntheticClass(en).members.forEach { declaredMembers.add(it.name) }
        }

        val builtinTopLevel = linkedSetOf<String>()
        for (mod in importedModules) {
            BuiltinDocRegistry.docsForModule(mod).forEach { builtinTopLevel.add(it.name) }
        }

        return diagnostics.filterNot { diag ->
            val msg = diag.message
            if (msg.startsWith("unresolved name: ")) {
                val name = msg.removePrefix("unresolved name: ").trim()
                name in declaredTopLevel || name in builtinTopLevel
            } else if (msg.startsWith("unresolved member: ")) {
                val name = msg.removePrefix("unresolved member: ").trim()
                val range = diag.range
                val dotLeft = if (range != null) DocLookupUtils.findDotLeft(text, range.start) else null
                dotLeft != null && name in declaredMembers
            } else {
                false
            }
        }
    }
}
