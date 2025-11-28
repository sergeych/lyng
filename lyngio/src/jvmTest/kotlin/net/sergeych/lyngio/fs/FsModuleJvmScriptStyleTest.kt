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
package net.sergeych.lyngio.fs

import kotlinx.coroutines.runBlocking
import net.sergeych.lyng.ExecutionError
import net.sergeych.lyng.Scope
import net.sergeych.lyng.io.fs.createFs
import net.sergeych.lyng.obj.ObjIllegalOperationException
import net.sergeych.lyngio.fs.security.*
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FsModuleJvmScriptStyleTest {

    private fun newScope(): Scope = Scope.new()

    private suspend fun installModule(scope: Scope) {
        createFs(PermitAllAccessPolicy, scope)
        scope.eval("import lyng.io.fs")
    }

    @Test
    fun basicTextIoScript() {
        runBlocking {
            val scope = newScope()
            installModule(scope)

            val dir = createTempDirectory("lyngio_script_basic_")
            try {
                val file = dir.resolve("hello.txt")
                scope.eval(
                    """
                    import lyng.io.fs
                    import lyng.stdlib
                    
                    val p = Path("${file}")
                    assertEquals(false, p.exists())
                    p.writeUtf8("Hello from Lyng")
                    assertEquals(true, p.exists())
                    assertEquals("Hello from Lyng", p.readUtf8())
                    println(p.name)
                    assertEquals("hello.txt", p.name)
                    assert(p.segments is List  )
                    assert(p.parent is Path)
                    """.trimIndent()
                )
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun listGlobAndMetadataScript() {
        runBlocking {
            val scope = newScope()
            installModule(scope)

            val dir = createTempDirectory("lyngio_script_glob_")
            try {
                val a = dir.resolve("a").toFile().apply { mkdirs() }
                dir.resolve("a/b").toFile().apply { mkdirs() }
                dir.resolve("a/one.txt").writeText("1")
                dir.resolve("a/b/two.txt").writeText("2")
                dir.resolve("a/b/three.md").writeText("3")

                scope.eval(
                    """
                    import lyng.io.fs
                    val root = Path("${a}")
                    val list = root.list().toList()
                    val names = list.map { it.toString() }
                    var hasOne = false
                    var hasB = false
                    val it1 = names.iterator()
                    while (it1.hasNext()) {
                        val n = it1.next()
                        if (n.endsWith("one.txt")) hasOne = true
                        if (n.endsWith("/b") || n.endsWith("\\b") || n.endsWith("b")) hasB = true
                    }
                    assertEquals(true, hasOne)
                    assertEquals(true, hasB)

                    val matches = root.glob("**/*.txt").toList()
                    val mnames = matches.map { it.toString() }
                    var hasOneTxt = false
                    var hasTwoTxt = false
                    val it2 = mnames.iterator()
                    while (it2.hasNext()) {
                        val n = it2.next()
                        if (n.endsWith("one.txt")) hasOneTxt = true
                        if (n.endsWith("two.txt")) hasTwoTxt = true
                    }
                    assertEquals(true, hasOneTxt)
                    assertEquals(true, hasTwoTxt)

                    val f = Path("${dir.resolve("a/one.txt")}")
                    val m = f.metadata()
                    assertEquals(true, m["isFile"])
                    """.trimIndent()
                )
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun streamingUtf8ChunksAndLinesScript() {
        runBlocking {
            val scope = newScope()
            installModule(scope)

            val dir = createTempDirectory("lyngio_script_stream_")
            try {
                val text = buildString {
                    repeat(5000) { append("строка-").append(it).append('\n') }
                }
                val tf = dir.resolve("big.txt"); tf.writeText(text)

                scope.eval(
                    """
                    import lyng.io.fs
                    val p = Path("${tf}")
                    var total = 0
                    val it = p.readUtf8Chunks(4096)
                    val iter = it.iterator()
                    while (iter.hasNext()) {
                        val chunk = iter.next()
                        total = total + chunk.size()
                    }
                    assertEquals(${text.length}, total)

                    var n = 0
                    var first = ""
                    var last = ""
                    val lit = p.lines()
                    val liter = lit.iterator()
                    while (liter.hasNext()) {
                        val ln = liter.next()
                        if (n == 0) first = ln
                        last = ln
                        n = n + 1
                    }
                    assertEquals(5000, n)
                    assertEquals("строка-0", first)
                    assertEquals("строка-4999", last)
                    """.trimIndent()
                )
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun denyingPolicyMappingScript() {
        runBlocking {
        val denyWrites = object : FsAccessPolicy {
            override suspend fun check(op: AccessOp, ctx: AccessContext): AccessDecision = when (op) {
                is AccessOp.OpenRead, is AccessOp.ListDir -> AccessDecision(Decision.Allow)
                else -> AccessDecision(Decision.Deny, reason = "denied by test policy")
            }
        }

        val prep = newScope()
        installModule(prep)
        val dir = createTempDirectory("lyngio_script_deny_")
        try {
            val file = dir.resolve("ro.txt"); file.writeText("ro")

            val scope = newScope()
            createFs(denyWrites, scope)
            scope.eval("import lyng.io.fs")

            // reading should succeed
            scope.eval(
                """
                import lyng.io.fs
                val p = Path("${file}")
                assertEquals("ro", p.readUtf8())
                """.trimIndent()
            )

            // writing should throw ExecutionError(ObjIllegalOperationException)
            val err = assertFailsWith<ExecutionError> {
                scope.eval(
                    """
                    import lyng.io.fs
                    // don't redeclare `p` to avoid same-scope conflicts across eval calls
                    Path("${file}").writeUtf8("x")
                    """.trimIndent()
                )
            }
            assertTrue((err as ExecutionError).errorObject is ObjIllegalOperationException)
        } finally {
            dir.toFile().deleteRecursively()
        }
        }
    }
}
