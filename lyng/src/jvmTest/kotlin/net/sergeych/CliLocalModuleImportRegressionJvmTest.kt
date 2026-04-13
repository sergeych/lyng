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

package net.sergeych

import kotlinx.coroutines.runBlocking
import net.sergeych.lyng.EvalSession
import net.sergeych.lyng.Source
import net.sergeych.lyng.obj.ObjString
import org.junit.After
import org.junit.Before
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliLocalModuleImportRegressionJvmTest {
    private val originalOut: PrintStream = System.out
    private val originalErr: PrintStream = System.err

    private class TestExit(val code: Int) : RuntimeException()

    @Before
    fun setUp() {
        jvmExitImpl = { code -> throw TestExit(code) }
    }

    @After
    fun tearDown() {
        System.setOut(originalOut)
        System.setErr(originalErr)
        jvmExitImpl = { code -> kotlin.system.exitProcess(code) }
    }

    private data class CliResult(val out: String, val err: String, val exitCode: Int?)

    private fun runCli(vararg args: String): CliResult {
        val outBuf = ByteArrayOutputStream()
        val errBuf = ByteArrayOutputStream()
        System.setOut(PrintStream(outBuf, true, Charsets.UTF_8))
        System.setErr(PrintStream(errBuf, true, Charsets.UTF_8))

        var exitCode: Int? = null
        try {
            runMain(arrayOf(*args))
        } catch (e: TestExit) {
            exitCode = e.code
        } finally {
            System.out.flush()
            System.err.flush()
        }
        return CliResult(outBuf.toString("UTF-8"), errBuf.toString("UTF-8"), exitCode)
    }

    private fun writeTransitiveImportTree(root: java.nio.file.Path) {
        val packageDir = Files.createDirectories(root.resolve("package1"))
        val nestedDir = Files.createDirectories(packageDir.resolve("nested"))

        packageDir.resolve("alpha.lyng").writeText(
            """
            package package1.alpha

            import lyng.stdlib
            import lyng.io.net

            class Alpha {
                val headers = Map<String, String>()

                fun makeTask(port: Int, host: String): Deferred = launch {
                    host + ":" + port
                }

                fun netModule() = Net
            }

            fun alphaValue() = "alpha"
            """.trimIndent()
        )
        packageDir.resolve("beta.lyng").writeText(
            """
            package package1.beta

            import lyng.stdlib
            import package1.alpha

            fun betaValue() = alphaValue() + "|beta"
            """.trimIndent()
        )
        nestedDir.resolve("gamma.lyng").writeText(
            """
            package package1.nested.gamma

            import lyng.io.net
            import package1.alpha
            import package1.beta

            val String.gammaTag get() = this + "|gamma"

            fun gammaValue() = betaValue().gammaTag
            fun netModule() = Net
            """.trimIndent()
        )
        packageDir.resolve("entry.lyng").writeText(
            """
            package package1.entry

            import lyng.stdlib
            import lyng.io.net
            import package1.alpha
            import package1.beta
            import package1.nested.gamma

            fun report() = gammaValue() + "|entry"
            """.trimIndent()
        )
    }

    private fun writeNestedLaunchImportBugTree(root: java.nio.file.Path) {
        val packageDir = Files.createDirectories(root.resolve("package1"))

        packageDir.resolve("alpha.lyng").writeText(
            """
            import lyng.io.net
            import package1.bravo

            class Alpha {
                val tcpServer: TcpServer
                val headers = Map<String, String>()

                fn startListen(port, host) {
                    tcpServer = Net.tcpListen(port, host)
            //        println("tcpServer.isOpen: " + tcpServer.isOpen())     // historical workaround; should not be needed
                    launch {
                        try {
                            while (true) {
                                val tcpSocket = tcpServer.accept()
                                var bravo = Bravo()
                                bravo.doSomething()
                                tcpSocket.close()
                                break
                            }
                        } finally {
                            tcpServer.close()
                        }
                    }
                }
            }
            """.trimIndent()
        )
        packageDir.resolve("bravo.lyng").writeText(
            """
            class Bravo {
                fn doSomething() {
                    println("Bravo.doSomething")
                }
            }
            """.trimIndent()
        )
    }

    @Test
    fun localModuleUsingLaunchAndNetImportsWithoutStdlibRedefinition() = runBlocking {
        val root = Files.createTempDirectory("lyng-cli-import-regression")
        try {
            val mainFile = root.resolve("main.lyng")
            writeTransitiveImportTree(root)
            mainFile.writeText(
                """
                import package1.entry
                import package1.beta
                import package1.nested.gamma

                println(report())
                """.trimIndent()
            )

            executeFile(mainFile.toString(), emptyList())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun localModuleImportsAreNoOpsWhenEvaldRepeatedlyOnSameCliContext() = runBlocking {
        val root = Files.createTempDirectory("lyng-cli-import-regression-repeat")
        try {
            val mainFile = root.resolve("main.lyng")
            writeTransitiveImportTree(root)
            mainFile.writeText("println(\"bootstrap\")")

            val session = EvalSession(newCliScope(emptyList(), mainFile.toString()))
            try {
                repeat(5) { index ->
                    val result = evalOnCliDispatcher(
                        session,
                        Source(
                            "<repeat-local-import-$index>",
                            """
                            import package1.entry
                            import package1.nested.gamma
                            import package1.beta
                            import package1.alpha

                            report()
                            """.trimIndent()
                        )
                    ) as ObjString

                    assertEquals(
                        "alpha|beta|gamma|entry",
                        result.value
                    )
                }
            } finally {
                session.cancelAndJoin()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun localModuleImportUsedOnlyInsideMethodLaunchClosureRemainsPrepared() = runBlocking {
        val root = Files.createTempDirectory("lyng-cli-import-regression-launch")
        try {
            val mainFile = root.resolve("main.lyng")
            val port = java.net.ServerSocket(0).let {
                val selected = it.localPort
                it.close()
                selected
            }
            writeNestedLaunchImportBugTree(root)
            mainFile.writeText(
                """
                import lyng.io.net
                import package1.alpha

                val alpha = Alpha()
                alpha.startListen($port, "127.0.0.1")

                delay(50)

                val socket = Net.tcpConnect("127.0.0.1", $port)
                socket.writeUtf8("ping")
                socket.flush()
                socket.close()

                delay(50)
                """.trimIndent()
            )

            val result = runCli(mainFile.toString())
            assertTrue(result.err.isBlank(), result.err)
            assertFalse(result.out.contains("module capture 'Bravo'"), result.out)
            assertTrue(result.out.contains("Bravo.doSomething"), result.out)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
