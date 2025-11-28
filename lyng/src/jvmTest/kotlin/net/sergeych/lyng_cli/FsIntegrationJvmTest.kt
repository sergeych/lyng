/*
 * Copyright 2025 Sergey S. Chernov real.sergeych@gmail.com
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
package net.sergeych.lyng_cli

import kotlinx.coroutines.runBlocking
import net.sergeych.baseScopeDefer
import org.junit.Test
import kotlin.io.path.createTempDirectory

class FsIntegrationJvmTest {

    @Test
    fun scopeHasFsModuleInstalled() {
        runBlocking {
            val scope = baseScopeDefer.await()
            // Ensure we can import the FS module and use Path bindings from Lyng script
            val dir = createTempDirectory("lyng_cli_fs_test_")
            try {
                val file = dir.resolve("hello.txt")
                // Drive the operation via Lyng code to validate bindings end-to-end
                scope.eval(
                    """
                    import lyng.io.fs
                    val p = Path("${'$'}{file}")
                    p.writeUtf8("hello from cli test")
                    assertEquals(true, p.exists())
                    assertEquals("hello from cli test", p.readUtf8())
                    """.trimIndent()
                )
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun scopeHasFsSeesRealFs() {
        runBlocking {
            val scope = baseScopeDefer.await()
            // Drive the operation via Lyng code to validate bindings end-to-end
            scope.eval(
                """
                    import lyng.io.fs
                    // list current folder files
                    println( Path(".").list().toList() )
                    """.trimIndent()
            )
        }
    }
}
