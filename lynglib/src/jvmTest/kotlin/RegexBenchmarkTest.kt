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
 * JVM micro-benchmark for regex caching under REGEX_CACHE.
 */

import kotlinx.coroutines.runBlocking
import net.sergeych.lyng.PerfFlags
import net.sergeych.lyng.Scope
import net.sergeych.lyng.obj.ObjInt
import kotlin.test.Test
import kotlin.test.assertEquals

class RegexBenchmarkTest {
    @Test
    fun benchmarkLiteralPatternMatches() = runBlocking {
        val n = 500_000
        val text = "abc123def"
        val pattern = ".*\\d{3}.*" // substring contains three digits
        val script = """
            val text = "$text"
            val pat = "$pattern"
            var s = 0
            var i = 0
            while (i < $n) {
                if (text.matches(pat)) { s = s + 1 }
                i = i + 1
            }
            s
        """.trimIndent()

        // OFF
        PerfFlags.REGEX_CACHE = false
        val scope1 = Scope()
        val t0 = System.nanoTime()
        val r1 = (scope1.eval(script) as ObjInt).value
        val t1 = System.nanoTime()
        println("[DEBUG_LOG] [BENCH] regex-literal x$n [REGEX_CACHE=OFF]: ${(t1 - t0)/1_000_000.0} ms")

        // ON
        PerfFlags.REGEX_CACHE = true
        val scope2 = Scope()
        val t2 = System.nanoTime()
        val r2 = (scope2.eval(script) as ObjInt).value
        val t3 = System.nanoTime()
        println("[DEBUG_LOG] [BENCH] regex-literal x$n [REGEX_CACHE=ON]: ${(t3 - t2)/1_000_000.0} ms")

        // "abc123def" matches \\d{3}
        val expected = 1L * n
        assertEquals(expected, r1)
        assertEquals(expected, r2)
    }

    @Test
    fun benchmarkDynamicPatternMatches() = runBlocking {
        val n = 300_000
        val text = "foo-123-XYZ"
        val patterns = listOf("foo-\\d{3}-XYZ", "bar-\\d{3}-XYZ")
        val script = """
            val text = "$text"
            val patterns = ["foo-\\d{3}-XYZ","bar-\\d{3}-XYZ"]
            var s = 0
            var i = 0
            while (i < $n) {
                // Alternate patterns to exercise cache
                val p = if (i % 2 == 0) patterns[0] else patterns[1]
                if (text.matches(p)) { s = s + 1 }
                i = i + 1
            }
            s
        """.trimIndent()

        // OFF
        PerfFlags.REGEX_CACHE = false
        val scope1 = Scope()
        val t0 = System.nanoTime()
        val r1 = (scope1.eval(script) as ObjInt).value
        val t1 = System.nanoTime()
        println("[DEBUG_LOG] [BENCH] regex-dynamic x$n [REGEX_CACHE=OFF]: ${(t1 - t0)/1_000_000.0} ms")

        // ON
        PerfFlags.REGEX_CACHE = true
        val scope2 = Scope()
        val t2 = System.nanoTime()
        val r2 = (scope2.eval(script) as ObjInt).value
        val t3 = System.nanoTime()
        println("[DEBUG_LOG] [BENCH] regex-dynamic x$n [REGEX_CACHE=ON]: ${(t3 - t2)/1_000_000.0} ms")

        // Only the first pattern matches; alternates every other iteration
        val expected = (n / 2).toLong()
        assertEquals(expected, r1)
        assertEquals(expected, r2)
    }
}
