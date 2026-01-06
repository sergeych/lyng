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
import net.sergeych.lyng.Compiler
import net.sergeych.lyng.Source
import net.sergeych.lyng.binding.Binder
import net.sergeych.lyng.binding.BindingSnapshot
import net.sergeych.lyng.miniast.MiniAstBuilder
import net.sergeych.lyng.miniast.MiniScript

object LyngAstManager {
    private val MINI_KEY = Key.create<MiniScript>("lyng.mini.cache")
    private val BINDING_KEY = Key.create<BindingSnapshot>("lyng.binding.cache")
    private val STAMP_KEY = Key.create<Long>("lyng.mini.cache.stamp")

    fun getMiniAst(file: PsiFile): MiniScript? = runReadAction {
        val vFile = file.virtualFile ?: return@runReadAction null
        val combinedStamp = getCombinedStamp(file)

        val prevStamp = file.getUserData(STAMP_KEY)
        val cached = file.getUserData(MINI_KEY)
        if (cached != null && prevStamp != null && prevStamp == combinedStamp) return@runReadAction cached

        val text = file.viewProvider.contents.toString()
        val sink = MiniAstBuilder()
        val built = try {
            val provider = IdeLenientImportProvider.create()
            val src = Source(file.name, text)
            runBlocking { Compiler.compileWithMini(src, provider, sink) }
            val script = sink.build()
            if (script != null && !file.name.endsWith(".lyng.d")) {
                val dFiles = collectDeclarationFiles(file)
                for (df in dFiles) {
                    val scriptD = getMiniAst(df)
                    if (scriptD != null) {
                        script.declarations.addAll(scriptD.declarations)
                        script.imports.addAll(scriptD.imports)
                    }
                }
            }
            script
        } catch (_: Throwable) {
            sink.build()
        }

        if (built != null) {
            file.putUserData(MINI_KEY, built)
            file.putUserData(STAMP_KEY, combinedStamp)
            // Invalidate binding too
            file.putUserData(BINDING_KEY, null)
        }
        built
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
        val vFile = file.virtualFile ?: return@runReadAction null
        var combinedStamp = file.viewProvider.modificationStamp

        val dFiles = if (!file.name.endsWith(".lyng.d")) collectDeclarationFiles(file) else emptyList()
        for (df in dFiles) {
            combinedStamp += df.viewProvider.modificationStamp
        }

        val prevStamp = file.getUserData(STAMP_KEY)
        val cached = file.getUserData(BINDING_KEY)

        if (cached != null && prevStamp != null && prevStamp == combinedStamp) return@runReadAction cached

        val mini = getMiniAst(file) ?: return@runReadAction null
        val text = file.viewProvider.contents.toString()
        val binding = try {
            Binder.bind(text, mini)
        } catch (_: Throwable) {
            null
        }

        if (binding != null) {
            file.putUserData(BINDING_KEY, binding)
            // stamp is already set by getMiniAst or we set it here if getMiniAst was cached
            file.putUserData(STAMP_KEY, combinedStamp)
        }
        binding
    }
}
