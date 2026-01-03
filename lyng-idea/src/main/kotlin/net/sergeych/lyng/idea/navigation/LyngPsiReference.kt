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

package net.sergeych.lyng.idea.navigation

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import net.sergeych.lyng.highlight.offsetOf
import net.sergeych.lyng.idea.util.LyngAstManager
import net.sergeych.lyng.idea.util.TextCtx
import net.sergeych.lyng.miniast.*

class LyngPsiReference(element: PsiElement) : PsiPolyVariantReferenceBase<PsiElement>(element, TextRange(0, element.textLength)) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val file = element.containingFile
        val text = file.text
        val offset = element.textRange.startOffset
        val name = element.text ?: ""
        val results = mutableListOf<ResolveResult>()

        val mini = LyngAstManager.getMiniAst(file) ?: return emptyArray()
        val binding = LyngAstManager.getBinding(file)

        // 1. Member resolution (obj.member)
        val dotPos = TextCtx.findDotLeft(text, offset)
        if (dotPos != null) {
            val imported = DocLookupUtils.canonicalImportedModules(mini, text)
            val receiverClass = DocLookupUtils.guessReceiverClassViaMini(mini, text, dotPos, imported, binding)
                ?: DocLookupUtils.guessReceiverClass(text, dotPos, imported, mini)
            
            if (receiverClass != null) {
                val resolved = DocLookupUtils.resolveMemberWithInheritance(imported, receiverClass, name, mini)
                if (resolved != null) {
                    val owner = resolved.first
                    val member = resolved.second
                    
                    // We need to find the actual PSI element for this member
                    val targetFile = findFileForClass(file.project, owner) ?: file
                    val targetMini = LyngAstManager.getMiniAst(targetFile)
                    if (targetMini != null) {
                        val targetSrc = targetMini.range.start.source
                        val off = targetSrc.offsetOf(member.nameStart)
                        targetFile.findElementAt(off)?.let {
                            val kind = when(member) {
                                is MiniMemberFunDecl -> "Function"
                                is MiniMemberValDecl -> if (member.mutable) "Variable" else "Value"
                                is MiniInitDecl -> "Initializer"
                            }
                            results.add(PsiElementResolveResult(LyngDeclarationElement(it, member.name, kind)))
                        }
                    }
                }
            }
            // If we couldn't resolve exactly, we might still want to search globally but ONLY for members
            if (results.isEmpty()) {
                results.addAll(resolveGlobally(file.project, name, membersOnly = true))
            }
        } else {
            // 2. Local resolution via Binder
            if (binding != null) {
                val ref = binding.references.firstOrNull { offset >= it.start && offset < it.end }
                if (ref != null) {
                    val sym = binding.symbols.firstOrNull { it.id == ref.symbolId }
                    if (sym != null && sym.declStart >= 0) {
                        file.findElementAt(sym.declStart)?.let {
                            results.add(PsiElementResolveResult(LyngDeclarationElement(it, sym.name, sym.kind.name)))
                        }
                    }
                }
            }

            // 3. Global project scan
            // Only search globally if we haven't found a strong local match
            if (results.isEmpty()) {
                results.addAll(resolveGlobally(file.project, name))
            }
        }

        // 4. Filter results to exclude duplicates
        // Use a more robust de-duplication that prefers the raw element if multiple refer to the same thing
        val filtered = mutableListOf<ResolveResult>()
        for (res in results) {
            val el = res.element ?: continue
            val nav = if (el is LyngDeclarationElement) el.navigationElement else el
            if (filtered.none { existing -> 
                val exEl = existing.element
                val exNav = if (exEl is LyngDeclarationElement) exEl.navigationElement else exEl
                exNav == nav || (exNav != null && exNav.isEquivalentTo(nav))
            }) {
                filtered.add(res)
            }
        }
        
        return filtered.toTypedArray()
    }

    private fun findFileForClass(project: Project, className: String): PsiFile? {
        val psiManager = PsiManager.getInstance(project)
        
        // 1. Try file with matching name first (optimization)
        val matchingFiles = FilenameIndex.getFilesByName(project, "$className.lyng", GlobalSearchScope.projectScope(project))
        for (file in matchingFiles) {
            val mini = LyngAstManager.getMiniAst(file) ?: continue
            if (mini.declarations.any { (it is MiniClassDecl && it.name == className) || (it is MiniEnumDecl && it.name == className) }) {
                return file
            }
        }

        // 2. Fallback to full project scan
        val allFiles = FilenameIndex.getAllFilesByExt(project, "lyng", GlobalSearchScope.projectScope(project))
        for (vFile in allFiles) {
            val file = psiManager.findFile(vFile) ?: continue
            if (matchingFiles.contains(file)) continue // already checked
            val mini = LyngAstManager.getMiniAst(file) ?: continue
            if (mini.declarations.any { (it is MiniClassDecl && it.name == className) || (it is MiniEnumDecl && it.name == className) }) {
                return file
            }
        }
        return null
    }

    override fun resolve(): PsiElement? {
        val results = multiResolve(false)
        if (results.isEmpty()) return null
        val target = results[0].element ?: return null
        // If the target is equivalent to our source element, return the source element itself.
        // This is crucial for IDEA to recognize we are already at the declaration site
        // and trigger "Show Usages" instead of performing a no-op navigation.
        if (target == element || target.isEquivalentTo(element)) {
            return element
        }
        return target
    }

    private fun resolveGlobally(project: Project, name: String, membersOnly: Boolean = false): List<ResolveResult> {
        val results = mutableListOf<ResolveResult>()
        val files = FilenameIndex.getAllFilesByExt(project, "lyng", GlobalSearchScope.projectScope(project))
        val psiManager = PsiManager.getInstance(project)

        for (vFile in files) {
            val file = psiManager.findFile(vFile) ?: continue
            val mini = LyngAstManager.getMiniAst(file) ?: continue
            val src = mini.range.start.source

            fun addIfMatch(dName: String, nameStart: net.sergeych.lyng.Pos, dKind: String) {
                if (dName == name) {
                    val off = src.offsetOf(nameStart)
                    file.findElementAt(off)?.let {
                        results.add(PsiElementResolveResult(LyngDeclarationElement(it, dName, dKind)))
                    }
                }
            }

            for (d in mini.declarations) {
                if (!membersOnly) {
                    val dKind = when(d) {
                        is net.sergeych.lyng.miniast.MiniFunDecl -> "Function"
                        is net.sergeych.lyng.miniast.MiniClassDecl -> "Class"
                        is net.sergeych.lyng.miniast.MiniEnumDecl -> "Enum"
                        is net.sergeych.lyng.miniast.MiniValDecl -> if (d.mutable) "Variable" else "Value"
                    }
                    addIfMatch(d.name, d.nameStart, dKind)
                }
                
                // Check members of classes and enums
                val members = when(d) {
                    is MiniClassDecl -> d.members
                    is MiniEnumDecl -> DocLookupUtils.enumToSyntheticClass(d).members
                    else -> emptyList()
                }
                
                for (m in members) {
                    val mKind = when(m) {
                        is net.sergeych.lyng.miniast.MiniMemberFunDecl -> "Function"
                        is net.sergeych.lyng.miniast.MiniMemberValDecl -> if (m.mutable) "Variable" else "Value"
                        is net.sergeych.lyng.miniast.MiniInitDecl -> "Initializer"
                    }
                    addIfMatch(m.name, m.nameStart, mKind)
                }
            }
        }
        return results
    }

    override fun getVariants(): Array<Any> = emptyArray()
}
