package net.sergeych

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test

class CliLocalModuleImportRegressionJvmTest {

    @Test
    fun localModuleUsingLaunchAndNetImportsWithoutStdlibRedefinition() = runBlocking {
        val root = Files.createTempDirectory("lyng-cli-import-regression")
        try {
            val packageDir = Files.createDirectories(root.resolve("package1"))
            val mainFile = root.resolve("main.lyng")
            val alphaFile = packageDir.resolve("alpha.lyng")

            mainFile.writeText(
                """
                import package1.alpha

                println("ok")
                """.trimIndent()
            )
            alphaFile.writeText(
                """
                import lyng.io.net

                class Alpha {
                    val headers = Map<String, String>()

                    fn startListen(port, host) {
                        launch {
                            println(port, host)
                        }
                    }
                }
                """.trimIndent()
            )

            executeFile(mainFile.toString(), emptyList())
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
