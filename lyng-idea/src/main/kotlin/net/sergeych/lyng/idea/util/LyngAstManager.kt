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

    fun getMiniAst(file: PsiFile): MiniScript? {
        val doc = file.viewProvider.document ?: return null
        val stamp = doc.modificationStamp
        val prevStamp = file.getUserData(STAMP_KEY)
        val cached = file.getUserData(MINI_KEY)
        if (cached != null && prevStamp != null && prevStamp == stamp) return cached

        val text = doc.text
        val sink = MiniAstBuilder()
        val built = try {
            val provider = IdeLenientImportProvider.create()
            val src = Source(file.name, text)
            runBlocking { Compiler.compileWithMini(src, provider, sink) }
            val script = sink.build()
            if (script != null && !file.name.endsWith(".lyng.d")) {
                mergeDeclarationFiles(file, script)
            }
            script
        } catch (_: Throwable) {
            sink.build()
        }

        if (built != null) {
            file.putUserData(MINI_KEY, built)
            file.putUserData(STAMP_KEY, stamp)
            // Invalidate binding too
            file.putUserData(BINDING_KEY, null)
        }
        return built
    }

    private fun mergeDeclarationFiles(file: PsiFile, mainScript: MiniScript) {
        val psiManager = PsiManager.getInstance(file.project)
        var current = file.virtualFile?.parent
        val seen = mutableSetOf<String>()

        while (current != null) {
            for (child in current.children) {
                if (child.name.endsWith(".lyng.d") && child != file.virtualFile && seen.add(child.path)) {
                    val psiD = psiManager.findFile(child) ?: continue
                    val scriptD = getMiniAst(psiD)
                    if (scriptD != null) {
                        mainScript.declarations.addAll(scriptD.declarations)
                        mainScript.imports.addAll(scriptD.imports)
                    }
                }
            }
            current = current.parent
        }
    }

    fun getBinding(file: PsiFile): BindingSnapshot? {
        val doc = file.viewProvider.document ?: return null
        val stamp = doc.modificationStamp
        val prevStamp = file.getUserData(STAMP_KEY)
        val cached = file.getUserData(BINDING_KEY)
        
        if (cached != null && prevStamp != null && prevStamp == stamp) return cached
        
        val mini = getMiniAst(file) ?: return null
        val text = doc.text
        val binding = try {
            Binder.bind(text, mini)
        } catch (_: Throwable) {
            null
        }
        
        if (binding != null) {
            file.putUserData(BINDING_KEY, binding)
            // stamp is already set by getMiniAst
        }
        return binding
    }
}
