package net.sergeych

import net.sergeych.lyng.EvalSession
import net.sergeych.lyng.Source
import net.sergeych.lyng.obj.ObjString
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class CliLocalModuleImportRegressionJvmTest {

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
}
