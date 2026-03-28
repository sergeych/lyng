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
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
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
import net.sergeych.lyng.idea.LyngIcons
import java.io.File

class RunLyngScriptAction : AnAction(LyngIcons.FILE) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun getPsiFile(e: AnActionEvent): PsiFile? {
        val project = e.project ?: return null
        return e.getData(CommonDataKeys.PSI_FILE) ?: run {
            val vf = e.getData(CommonDataKeys.VIRTUAL_FILE)
            if (vf != null) PsiManager.getInstance(project).findFile(vf) else null
        }
    }

    private fun getRunnableFile(e: AnActionEvent): PsiFile? {
        val psiFile = getPsiFile(e) ?: return null
        val virtualFile = psiFile.virtualFile ?: return null
        if (!virtualFile.isInLocalFileSystem) return null
        if (!psiFile.name.endsWith(".lyng")) return null
        return psiFile
    }

    override fun update(e: AnActionEvent) {
        val psiFile = getRunnableFile(e)
        val isRunnable = psiFile != null
        e.presentation.isEnabledAndVisible = isRunnable
        if (isRunnable) {
            e.presentation.text = "Run '${psiFile.name}'"
            e.presentation.description = "Run the current Lyng script using the Lyng CLI"
        } else {
            e.presentation.text = "Run Lyng Script"
            e.presentation.description = "Run the current Lyng script"
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val psiFile = getRunnableFile(e) ?: return
        val virtualFile = psiFile.virtualFile ?: return
        FileDocumentManager.getInstance().getDocument(virtualFile)?.let { document ->
            FileDocumentManager.getInstance().saveDocument(document)
        }
        val filePath = virtualFile.path
        val workingDir = virtualFile.parent?.path ?: project.basePath ?: File(filePath).parent

        val (console, toolWindow) = getConsoleAndToolWindow(project)
        console.clear()

        toolWindow.show {
            scope.launch {
                val command = startLyngProcess(filePath, workingDir)
                if (command == null) {
                    printToConsole(console, "Unable to start Lyng CLI.\n", ConsoleViewContentType.ERROR_OUTPUT)
                    printToConsole(console, "Tried commands: lyng, jlyng.\n", ConsoleViewContentType.ERROR_OUTPUT)
                    printToConsole(console, "Install `lyng` or `jlyng` and make sure it is available on PATH.\n", ConsoleViewContentType.NORMAL_OUTPUT)
                    return@launch
                }

                printToConsole(
                    console,
                    "Running ${command.commandLine} in ${command.workingDir}\n",
                    ConsoleViewContentType.SYSTEM_OUTPUT
                )
                streamProcess(command.process, console)
                val exitCode = command.process.waitFor()
                val outputType = if (exitCode == 0) ConsoleViewContentType.SYSTEM_OUTPUT else ConsoleViewContentType.ERROR_OUTPUT
                printToConsole(console, "\nProcess finished with exit code $exitCode\n", outputType)
            }
        }
    }

    private suspend fun streamProcess(process: Process, console: ConsoleView) {
        val stdout = scope.launch {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { printToConsole(console, "$it\n", ConsoleViewContentType.NORMAL_OUTPUT) }
            }
        }
        val stderr = scope.launch {
            process.errorStream.bufferedReader().useLines { lines ->
                lines.forEach { printToConsole(console, "$it\n", ConsoleViewContentType.ERROR_OUTPUT) }
            }
        }
        stdout.join()
        stderr.join()
    }

    private fun printToConsole(console: ConsoleView, text: String, type: ConsoleViewContentType) {
        ApplicationManager.getApplication().invokeLater {
            console.print(text, type)
        }
    }

    private fun startLyngProcess(filePath: String, workingDir: String?): StartedProcess? {
        val candidates = listOf("lyng", "jlyng")
        for (candidate in candidates) {
            try {
                val process = ProcessBuilder(candidate, filePath)
                    .directory(workingDir?.let(::File))
                    .start()
                return StartedProcess(process, "$candidate $filePath", workingDir ?: File(filePath).parent.orEmpty())
            } catch (_: java.io.IOException) {
                // Try the next candidate when the command is not available.
            }
        }
        return null
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

    private data class StartedProcess(
        val process: Process,
        val commandLine: String,
        val workingDir: String
    )
}
