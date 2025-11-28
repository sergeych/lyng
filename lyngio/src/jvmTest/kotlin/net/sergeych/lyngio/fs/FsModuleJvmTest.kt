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

/*
 * JVM tests for lyng.io.fs module bindings.
 */
package net.sergeych.lyngio.fs

import kotlinx.coroutines.runBlocking
import net.sergeych.lyng.ExecutionError
import net.sergeych.lyng.Scope
import net.sergeych.lyng.io.fs.createFs
import net.sergeych.lyng.obj.*
import net.sergeych.lyngio.fs.security.*
import kotlin.io.path.*
import kotlin.test.*

class FsModuleJvmTest {

    private fun newScope(): Scope = Scope.new()

    private suspend fun importFs(scope: Scope) {
        // Ensure module is installed and imported into the scope
        val installed = createFs(PermitAllAccessPolicy, scope)
        // ok to be false if already installed for this manager, but in tests we create a fresh scope
        scope.eval("import lyng.io.fs")
    }

    private suspend fun pathObj(scope: Scope, path: String): Obj {
        val pathClass = scope.get("Path")!!.value as ObjClass
        return pathClass.callWithArgs(scope, ObjString(path))
    }

    @Test
    fun installerIdempotence() = runBlocking {
        val scope = newScope()
        val m = scope.importManager
        assertTrue(createFs(PermitAllAccessPolicy, m))
        assertFalse(createFs(PermitAllAccessPolicy, m))
    }

    @Test
    fun basicReadWriteUtf8AndExists() = runBlocking {
        val scope = newScope()
        importFs(scope)

        val tmpDir = kotlin.io.path.createTempDirectory("lyngio_test_")
        try {
            val file = tmpDir.resolve("hello.txt")
            val p = pathObj(scope, file.toString())
            // Initially doesn't exist
            assertFalse(p.invokeInstanceMethod(scope, "exists").toBool())
            // Write and read
            p.invokeInstanceMethod(scope, "writeUtf8", ObjString("Hello Lyng!"))
            assertTrue(p.invokeInstanceMethod(scope, "exists").toBool())
            val read = p.invokeInstanceMethod(scope, "readUtf8") as ObjString
            assertEquals("Hello Lyng!", read.value)
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun bytesReadWriteAndMetadata() = runBlocking {
        val scope = newScope()
        importFs(scope)

        val tmpDir = kotlin.io.path.createTempDirectory("lyngio_test_bytes_")
        try {
            val file = tmpDir.resolve("data.bin")
            val dirPath = pathObj(scope, tmpDir.toString())
            val p = pathObj(scope, file.toString())

            // mkdirs on parent dir is no-op but should succeed
            dirPath.invokeInstanceMethod(scope, "mkdirs")

            val bytes = (0 until 256).map { it.toByte() }.toByteArray().asUByteArray()
            p.invokeInstanceMethod(scope, "writeBytes", ObjBuffer(bytes))

            val readBuf = p.invokeInstanceMethod(scope, "readBytes") as ObjBuffer
            assertContentEquals(bytes.asByteArray(), readBuf.byteArray.asByteArray())

            val meta = p.invokeInstanceMethod(scope, "metadata") as ObjMap
            assertEquals(ObjBool(true), meta.map[ObjString("isFile")])
            assertEquals(ObjBool(false), meta.map[ObjString("isDirectory")])
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun listAndGlob() = runBlocking {
        val scope = newScope()
        importFs(scope)

        val tmpDir = kotlin.io.path.createTempDirectory("lyngio_test_glob_")
        try {
            // prepare dirs/files
            val sub1 = tmpDir.resolve("a"); sub1.createDirectories()
            val sub2 = tmpDir.resolve("a/b"); sub2.createDirectories()
            val f1 = tmpDir.resolve("a/one.txt"); f1.writeText("1")
            val f2 = tmpDir.resolve("a/b/two.txt"); f2.writeText("2")
            val f3 = tmpDir.resolve("a/b/three.md"); f3.writeText("3")

            val root = pathObj(scope, sub1.toString())

            // list should contain children: one.txt and b
            val list = root.invokeInstanceMethod(scope, "list") as ObjList
            val names = list.list.map { it.toString() }
            assertTrue(names.any { it.endsWith("one.txt") })
            assertTrue(names.any { it.endsWith("b") })

            // glob **/*.txt
            val matches = root.invokeInstanceMethod(scope, "glob", ObjString("**/*.txt")) as ObjList
            val mnames = matches.list.map { it.toString() }
            assertTrue(mnames.any { it.endsWith("one.txt") })
            assertTrue(mnames.any { it.endsWith("two.txt") })
            assertFalse(mnames.any { it.endsWith("three.md") })
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun copyMoveDelete() = runBlocking {
        val scope = newScope()
        importFs(scope)

        val tmpDir = kotlin.io.path.createTempDirectory("lyngio_test_cmd_")
        try {
            val f1 = tmpDir.resolve("src.txt"); f1.writeText("abc")
            val f2 = tmpDir.resolve("dst.txt")
            val p1 = pathObj(scope, f1.toString())
            val p2 = pathObj(scope, f2.toString())

            // copy
            p1.invokeInstanceMethod(scope, "copy", p2)
            assertTrue(f2.exists())
            assertEquals("abc", f2.readText())

            // move with overwrite
            val f3 = tmpDir.resolve("moved.txt"); f3.writeText("x")
            val p3 = pathObj(scope, f3.toString())
            p2.invokeInstanceMethod(scope, "move", p3, ObjBool(true))
            assertFalse(f2.exists())
            assertEquals("abc", f3.readText())

            // delete
            p3.invokeInstanceMethod(scope, "delete")
            assertFalse(f3.exists())
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun streamingReaders() = runBlocking {
        val scope = newScope()
        importFs(scope)

        val tmpDir = kotlin.io.path.createTempDirectory("lyngio_test_stream_")
        try {
            val large = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
            val file = tmpDir.resolve("big.bin"); file.writeBytes(large)
            val p = pathObj(scope, file.toString())

            // readChunks
            val it = p.invokeInstanceMethod(scope, "readChunks", ObjInt(131072)) // 128KB
            var total = 0
            val iter = it.invokeInstanceMethod(scope, "iterator")
            while (iter.invokeInstanceMethod(scope, "hasNext").toBool()) {
                val chunk = iter.invokeInstanceMethod(scope, "next") as ObjBuffer
                total += chunk.byteArray.size
                if (total >= 512 * 1024) {
                    // test cancellation early
                    iter.invokeInstanceMethod(scope, "cancelIteration")
                    break
                }
            }
            assertTrue(total >= 512 * 1024)

            // readUtf8Chunks + lines
            val text = buildString {
                repeat(10000) { append("line-").append(it).append('\n') }
            }
            val tf = tmpDir.resolve("big.txt"); tf.writeText(text)
            val tp = pathObj(scope, tf.toString())

            val lit = tp.invokeInstanceMethod(scope, "lines")
            val lines = mutableListOf<String>()
            val lIter = lit.invokeInstanceMethod(scope, "iterator")
            while (lIter.invokeInstanceMethod(scope, "hasNext").toBool()) {
                val s = lIter.invokeInstanceMethod(scope, "next") as ObjString
                lines += s.value
            }
            assertEquals(10000, lines.size)
            assertEquals("line-0", lines.first())
            assertEquals("line-9999", lines.last())
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun forbiddenOperationsRaiseLyngErrors() = runBlocking {
        // Policy that denies everything except read and list
        val denyWrites = object : FsAccessPolicy {
            override suspend fun check(op: AccessOp, ctx: AccessContext): AccessDecision = when (op) {
                is AccessOp.OpenRead, is AccessOp.ListDir -> AccessDecision(Decision.Allow)
                else -> AccessDecision(Decision.Deny, reason = "denied by test policy")
            }
        }

        // Prepare a file with PermitAll first (separate manager/scope)
        val prepScope = newScope()
        importFs(prepScope)
        val tmpDir = kotlin.io.path.createTempDirectory("lyngio_test_deny_")
        try {
            val file = tmpDir.resolve("ro.txt"); file.writeText("ro")

            // New scope with denying policy
            val scope = newScope()
            assertTrue(net.sergeych.lyng.io.fs.createFs(denyWrites, scope))
            scope.eval("import lyng.io.fs")

            val p = pathObj(scope, file.toString())
            // Read should work
            val text = p.invokeInstanceMethod(scope, "readUtf8") as ObjString
            assertEquals("ro", text.value)

            // Write should throw ExecutionError with ObjIllegalOperationException
            val err = assertFailsWith<ExecutionError> {
                p.invokeInstanceMethod(scope, "writeUtf8", ObjString("x"))
            }
            assertTrue(err.errorObject is ObjIllegalOperationException)
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }
}
