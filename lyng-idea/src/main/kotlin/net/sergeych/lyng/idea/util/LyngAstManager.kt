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
import kotlinx.coroutines.runBlocking
import net.sergeych.lyng.binding.BindingSnapshot
import net.sergeych.lyng.miniast.DocLookupUtils
import net.sergeych.lyng.miniast.MiniScript
import net.sergeych.lyng.tools.IdeLenientImportProvider
import net.sergeych.lyng.tools.LyngAnalysisRequest
import net.sergeych.lyng.tools.LyngAnalysisResult
import net.sergeych.lyng.tools.LyngLanguageTools

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
        var current = file.virtualFile?.parent
        val seen = mutableSetOf<String>()
        val result = mutableListOf<PsiFile>()

        while (current != null) {
            for (child in current.children) {
                if (child.name.endsWith(".lyng.d") && child != file.virtualFile && seen.add(child.path)) {
                    val psiD = psiManager.findFile(child) ?: continue
                    result.add(psiD)
                }
            }
            current = current.parent
        }
        result
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
            val merged = built.mini
            if (merged != null && !file.name.endsWith(".lyng.d")) {
                val dFiles = collectDeclarationFiles(file)
                for (df in dFiles) {
                    val dAnalysis = getAnalysis(df)
                    val dMini = dAnalysis?.mini ?: continue
                    merged.declarations.addAll(dMini.declarations)
                    merged.imports.addAll(dMini.imports)
                }
            }
            val finalAnalysis = if (merged != null) {
                built.copy(
                    mini = merged,
                    importedModules = DocLookupUtils.canonicalImportedModules(merged, text)
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
}
