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

package net.sergeych.lyng.idea.actions

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.ui.content.ContentFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.sergeych.lyng.ExecutionError
import net.sergeych.lyng.Script
import net.sergeych.lyng.Source
import net.sergeych.lyng.idea.LyngIcons
import net.sergeych.lyng.obj.ObjVoid
import net.sergeych.lyng.obj.getLyngExceptionMessageWithStackTrace

class RunLyngScriptAction : AnAction(LyngIcons.FILE) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private fun getPsiFile(e: AnActionEvent): PsiFile? {
        val project = e.project ?: return null
        return e.getData(CommonDataKeys.PSI_FILE) ?: run {
            val vf = e.getData(CommonDataKeys.VIRTUAL_FILE)
            if (vf != null) PsiManager.getInstance(project).findFile(vf) else null
        }
    }

    override fun update(e: AnActionEvent) {
        val psiFile = getPsiFile(e)
        val isLyng = psiFile?.name?.endsWith(".lyng") == true
        e.presentation.isEnabledAndVisible = isLyng
        if (isLyng) {
            e.presentation.text = "Run '${psiFile.name}'"
        } else {
            e.presentation.text = "Run Lyng Script"
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val psiFile = getPsiFile(e) ?: return
        val text = psiFile.text
        val fileName = psiFile.name

        val (console, toolWindow) = getConsoleAndToolWindow(project)
        console.clear()

        toolWindow.show {
            scope.launch {
                try {
                    val lyngScope = Script.newScope()
                    lyngScope.addFn("print") {
                        val sb = StringBuilder()
                        for ((i, arg) in args.list.withIndex()) {
                            if (i > 0) sb.append(" ")
                            sb.append(arg.toString(this).value)
                        }
                        console.print(sb.toString(), ConsoleViewContentType.NORMAL_OUTPUT)
                        ObjVoid
                    }
                    lyngScope.addFn("println") {
                        val sb = StringBuilder()
                        for ((i, arg) in args.list.withIndex()) {
                            if (i > 0) sb.append(" ")
                            sb.append(arg.toString(this).value)
                        }
                        console.print(sb.toString() + "\n", ConsoleViewContentType.NORMAL_OUTPUT)
                        ObjVoid
                    }

                    console.print("--- Running $fileName ---\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                    val result = lyngScope.eval(Source(fileName, text))
                    console.print("\n--- Finished with result: ${result.inspect(lyngScope)} ---\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                } catch (t: Throwable) {
                    console.print("\n--- Error ---\n", ConsoleViewContentType.ERROR_OUTPUT)
                    if( t is ExecutionError ) {
                        val m = t.errorObject.getLyngExceptionMessageWithStackTrace()
                        console.print(m, ConsoleViewContentType.ERROR_OUTPUT)
                    }
                    else
                        console.print(t.message ?: t.toString(), ConsoleViewContentType.ERROR_OUTPUT)
                    console.print("\n", ConsoleViewContentType.ERROR_OUTPUT)
                }
            }
        }
    }

    private fun getConsoleAndToolWindow(project: Project): Pair<ConsoleView, ToolWindow> {
        val toolWindowManager = ToolWindowManager.getInstance(project)
        var toolWindow = toolWindowManager.getToolWindow(ToolWindowId.RUN)
        if (toolWindow == null) {
            toolWindow = toolWindowManager.getToolWindow(ToolWindowId.MESSAGES_WINDOW)
        }
        if (toolWindow == null) {
            toolWindow = toolWindowManager.getToolWindow("Lyng")
        }
        val actualToolWindow = toolWindow ?: run {
            @Suppress("DEPRECATION")
            toolWindowManager.registerToolWindow("Lyng", true, ToolWindowAnchor.BOTTOM)
        }

        val contentManager = actualToolWindow.contentManager
        val existingContent = contentManager.findContent("Lyng Run")
        if (existingContent != null) {
            val console = existingContent.component as ConsoleView
            contentManager.setSelectedContent(existingContent)
            return console to actualToolWindow
        }

        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        val content = ContentFactory.getInstance().createContent(console.component, "Lyng Run", false)
        contentManager.addContent(content)
        contentManager.setSelectedContent(content)
        return console to actualToolWindow
    }
}
