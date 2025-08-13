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

import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.runBlocking
import net.sergeych.lyng.ModuleScope
import net.sergeych.lyng.Source
import net.sergeych.lyng.eval
import net.sergeych.lyng.pacman.InlineSourcesImportProvider
import net.sergeych.lyng.toSource
import kotlin.test.Test

class OtherTests {
    @Test
    fun testImports3() = runBlocking {
        val foosrc = """
            package lyng.foo
            
            import lyng.bar            
            
            fun foo() { "foo1" }
            """.trimIndent()
        val barsrc = """
            package lyng.bar
            
            fun bar() { "bar1" }
            """.trimIndent()
        val pm = InlineSourcesImportProvider(
            listOf(
            Source("foosrc", foosrc),
            Source("barsrc", barsrc),
        ))

        val src = """
            import lyng.foo
            
            foo() + " / " + bar()
            """.trimIndent().toSource("test")

        val scope = ModuleScope(pm, src)
        assertEquals("foo1 / bar1", scope.eval(src).toString())
    }

    @Test
    fun testInstantTruncation() = runBlocking {
        eval("""
            import lyng.time
            val t1 = Instant()
            val t2 = Instant()
//            assert( t1 != t2 )
            println(t1 - t2)
        """.trimIndent())
        Unit
    }

}