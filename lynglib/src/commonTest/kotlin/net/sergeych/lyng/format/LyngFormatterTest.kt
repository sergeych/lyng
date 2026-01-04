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
package net.sergeych.lyng.format

import kotlin.test.Test
import kotlin.test.assertEquals

class LyngFormatterTest {

    @Test
    fun reindent_simpleFunction() {
        val src = """
            fun test21() {
            21
            }
        """.trimIndent()

        val expected = """
            fun test21() {
                21
            }
        """.trimIndent()

        val cfg = LyngFormatConfig(indentSize = 4, useTabs = false)
        val out = LyngFormatter.reindent(src, cfg)
        assertEquals(expected, out)
        // Idempotent
        assertEquals(expected, LyngFormatter.reindent(out, cfg))
    }

    @Test
    fun wrapping_longCalls_noTrailingComma() {
        val longArg = "x1234567890x1234567890x1234567890x1234567890x1234567890"
        val src = """
            fun demo() {
                doSomething(${longArg}, ${longArg}, ${longArg})
            }
        """.trimIndent()

        val out = LyngFormatter.format(src, LyngFormatConfig(applyWrapping = true, maxLineLength = 60, continuationIndentSize = 4))
        // Should contain vertically wrapped args and no trailing comma before ')'
        val hasTrailingComma = out.lines().any { it.trimEnd().endsWith(",") && it.trimEnd().endsWith("),") }
        kotlin.test.assertFalse(hasTrailingComma, "Trailing comma before ')' must not be added")
        // Idempotency
        val out2 = LyngFormatter.format(out, LyngFormatConfig(applyWrapping = true, maxLineLength = 60, continuationIndentSize = 4))
        assertEquals(out, out2)
    }

    @Test
    fun wrapping_preservesCommentsAndStrings() {
        val arg1 = "a /*not a block comment*/"
        val arg2 = "\"(keep)\"" // string with parens
        val src = """
            fun demo() { doSomething($arg1, $arg2, c) // end comment }
        """.trimIndent()

        val formatted = LyngFormatter.format(src, LyngFormatConfig(applyWrapping = true, maxLineLength = 40, continuationIndentSize = 4))
        // Ensure the string literal remains intact
        kotlin.test.assertTrue(formatted.contains(arg2), "String literal must be preserved")
        // Ensure end-of-line comment remains
        kotlin.test.assertTrue(formatted.contains("// end comment"), "EOL comment must be preserved")
        // Idempotency
        val formatted2 = LyngFormatter.format(formatted, LyngFormatConfig(applyWrapping = true, maxLineLength = 40, continuationIndentSize = 4))
        assertEquals(formatted, formatted2)
    }

    @Test
    fun propertyBasedIdempotencyDeep() {
        // Deeper randomized mixes of blocks + lists + calls + operators
        val blocks = listOf(
            "if(flag){\nX\n}else{\nY\n}",
            "try{\nX\n}catch(e){\nY\n}",
            "fun g(){\nX\n}",
            "val grid = [[\n1,2\n],[3,4]]\nX",
            "doWork(A,B,C)\nX",
        )
        val leaves = listOf(
            "sum = a+b* c",
            "matrix[i][j] = i + j",
            "call( x, y , z )",
            "// comment line",
            "println(\"s\")",
        )
        val cfgIndent = LyngFormatConfig(indentSize = 4, continuationIndentSize = 4)
        val cfgSpace = LyngFormatConfig(indentSize = 4, continuationIndentSize = 4, applySpacing = true)

        fun assembleDeep(seed: Int): String {
            val n = 10 + (seed % 6)
            val sb = StringBuilder()
            var cur = "X"
            repeat(n) { k ->
                val b = blocks[(seed + k * 11) % blocks.size]
                val leaf = leaves[(seed + k * 7) % leaves.size]
                cur = b.replace("X", cur.replace("X", leaf))
            }
            sb.append(cur)
            return sb.toString()
        }

        repeat(12) { s ->
            val doc = assembleDeep(s)
            val r1 = LyngFormatter.reindent(doc, cfgIndent)
            val r2 = LyngFormatter.reindent(r1, cfgIndent)
            assertEquals(r1, r2, "Indent-only idempotency failed for seed=$s")

            val f1 = LyngFormatter.format(doc, cfgSpace)
            val f2 = LyngFormatter.format(f1, cfgSpace)
            assertEquals(f1, f2, "Spacing-enabled idempotency failed for seed=$s")
        }
    }

    @Test
    fun elseIf_chain() {
        val src = """
            if(true){
            if(false){
            a()
            }
            else   if(maybe){
            b()
            }
            else{
            c()
            }
            }
        """.trimIndent()

        val expected = """
            if (true) {
                if (false) {
                    a()
                }
                else if (maybe) {
                    b()
                }
                else {
                    c()
                }
            }
        """.trimIndent()

        val cfg = LyngFormatConfig(applySpacing = true)
        val out = LyngFormatter.format(src, cfg)
        assertEquals(expected, out)
        assertEquals(expected, LyngFormatter.format(out, cfg))
    }

    @Test
    fun tryCatchFinally_alignment() {
        val src = """
            try{
            risky()
            }
            catch(e){
            handle(e)
            }
            finally{
            cleanup()
            }
        """.trimIndent()

        val expected = """
            try {
                risky()
            }
            catch (e) {
                handle(e)
            }
            finally {
                cleanup()
            }
        """.trimIndent()

        val cfg = LyngFormatConfig(applySpacing = true)
        val out = LyngFormatter.format(src, cfg)
        assertEquals(expected, out)
        assertEquals(expected, LyngFormatter.format(out, cfg))
    }

    @Test
    fun bracketContinuation_basic() {
        val src = """
            val arr = [
            1,
            2,
            3
            ]
        """.trimIndent()

        val expected = """
            val arr = [
                1,
                2,
                3
            ]
        """.trimIndent()

        val cfg = LyngFormatConfig(indentSize = 4, continuationIndentSize = 4)
        val out = LyngFormatter.reindent(src, cfg)
        assertEquals(expected, out)
        assertEquals(expected, LyngFormatter.reindent(out, cfg))
    }

    @Test
    fun idempotency_mixedSnippet() {
        val src = """
            // mix
            if(true){ doIt( a,b ,c) }
            val s = "keep(  spaces  )" // comment
            try{ ok() }catch(e){ bad() }
        """.trimIndent()

        val cfg = LyngFormatConfig(applySpacing = true)
        val once = LyngFormatter.format(src, cfg)
        val twice = LyngFormatter.format(once, cfg)
        assertEquals(once, twice)
    }

    @Test
    fun nestedIfTryChains() {
        val src = """
            if(true){
            try{
            if(flag){
            op()
            } else{
            other()
            }
            }
            catch(e){
            handle(e)
            }
            finally{
            done()
            }
            }
        """.trimIndent()

        val expected = """
            if (true) {
                try {
                    if (flag) {
                        op()
                    } else {
                        other()
                    }
                }
                catch (e) {
                    handle(e)
                }
                finally {
                    done()
                }
            }
        """.trimIndent()

        val cfg = LyngFormatConfig(applySpacing = true)
        val out = LyngFormatter.format(src, cfg)
        assertEquals(expected, out)
        assertEquals(expected, LyngFormatter.format(out, cfg))
    }

    @Test
    fun continuationWithCommentsAndBlanks() {
        val src = """
            fun call() {
            doIt(
            a, // first

            b,
            // middle
            c
            )
            }
        """.trimIndent()

        val expected = """
            fun call() {
                doIt(
                    a, // first

                    b,
                    // middle
                    c
                )
            }
        """.trimIndent()

        val cfg = LyngFormatConfig(continuationIndentSize = 4)
        val out = LyngFormatter.reindent(src, cfg)
        assertEquals(expected, out)
        assertEquals(expected, LyngFormatter.reindent(out, cfg))
    }

    @Test
    fun propertyBasedIdempotencySmall() {
        // Build small random snippets from blocks and verify idempotency under both modes
        val parts = listOf(
            "if(true){\nX\n}",
            "try{\nX\n}catch(e){\nY\n}",
            "fun f(){\nX\n}",
            "doIt(A, B, C)",
        )
        val leaves = listOf("op()", "other()", "println(1)")
        val cfgIndentOnly = LyngFormatConfig()
        val cfgSpacing = LyngFormatConfig(applySpacing = true)
        repeat(20) { n ->
            var snippet = parts[(n + 0) % parts.size]
            snippet = snippet.replace("X", leaves[n % leaves.size])
            snippet = snippet.replace("Y", leaves[(n + 1) % leaves.size])
            val once1 = LyngFormatter.reindent(snippet, cfgIndentOnly)
            val twice1 = LyngFormatter.reindent(once1, cfgIndentOnly)
            assertEquals(once1, twice1)
            val once2 = LyngFormatter.format(snippet, cfgSpacing)
            val twice2 = LyngFormatter.format(once2, cfgSpacing)
            assertEquals(once2, twice2)
        }
    }

    @Test
    fun multiLineChainCalls() {
        val src = """
            somevar.where
            .isGreen()
            .isWarm()
        """.trimIndent()

        val expected = """
            somevar.where
                .isGreen()
                .isWarm()
        """.trimIndent()

        val cfg = LyngFormatConfig(indentSize = 4, continuationIndentSize = 8)
        val out = LyngFormatter.reindent(src, cfg)
        assertEquals(expected, out)
    }

    @Test
    fun chainedCalls_and_MemberAccess() {
        val src = """
            fun demo() {
            val r = obj.chain().next( a,b ,c).last().value
            doIt(a,b).then{ op() }.final()
            }
        """.trimIndent()

        val expected = """
            fun demo() {
                val r = obj.chain().next(a, b, c).last().value
                doIt(a, b).then{ op() }.final()
            }
        """.trimIndent()

        // Dots should remain tight; commas should be spaced
        val cfg = LyngFormatConfig(applySpacing = true)
        val out = LyngFormatter.format(src, cfg)
        assertEquals(expected, out)
        assertEquals(expected, LyngFormatter.format(out, cfg))
    }

    @Test
    fun nestedArrays_and_Calls() {
        val src = """
            val m = [[
            1,2], [3,4
            ], [5,
            6]
            ]
            fun f(){
            compute(
            m[0][1], m[1][0]
            )
            }
        """.trimIndent()

        val expected = """
            val m = [[
                1, 2], [3, 4
            ], [5,
                6]
            ]
            fun f(){
                compute(
                    m[0][1], m[1][0]
                )
            }
        """.trimIndent()

        // Indent under brackets and inside calls; commas spaced
        val out = LyngFormatter.reindent(src, LyngFormatConfig(indentSize = 4, continuationIndentSize = 4))
        val spaced = LyngFormatter.format(out, LyngFormatConfig(applySpacing = true))
        assertEquals(expected, spaced)
        assertEquals(expected, LyngFormatter.format(spaced, LyngFormatConfig(applySpacing = true)))
    }

    @Test
    fun mixedOperators_spacing() {
        val src = """
            fun calc(){
            val x =1+2* 3
            val y =x== 7||x!=8&&x<= 9
            if(true){ if(false){ a=b+ c }else{ a =b*c}}
            }
        """.trimIndent()

        val expected = """
            fun calc(){
                val x = 1 + 2 * 3
                val y = x == 7 || x != 8 && x <= 9
                if (true) { if (false) { a = b + c } else { a = b * c}}
            }
        """.trimIndent()

        val cfg = LyngFormatConfig(applySpacing = true)
        val out = LyngFormatter.format(src, cfg)
        assertEquals(expected, out)
        assertEquals(expected, LyngFormatter.format(out, cfg))
    }

    @Test
    fun multilineCommentIndentation() {
        val src = """
            /*
            This is
            a comment
            */
            fun f() {
                /*
                Inner
                comment
                */
                val x = 1 /* end of line
                comment */
            }
        """.trimIndent()

        val expected = """
            /*
                This is
                a comment
            */
            fun f() {
                /*
                    Inner
                    comment
                */
                val x = 1 /* end of line
                    comment */
            }
        """.trimIndent()

        val cfg = LyngFormatConfig(indentSize = 4)
        val out = LyngFormatter.reindent(src, cfg)
        assertEquals(expected, out)
    }

    @Test
    fun multicharacterOperators_spacing() {
        val operators = listOf("+=", "-=", "*=", "/=", "=>", "==", "!=", "<=", ">=", "&&", "||", "->")
        for (op in operators) {
            val src = "a${op}b"
            val expected = "a $op b"
            val cfg = LyngFormatConfig(applySpacing = true)
            val out = LyngFormatter.format(src, cfg)
            assertEquals(expected, out, "Failed for operator $op")
        }
    }

    @Test
    fun propertyBasedIdempotencyMedium() {
        // Compose slightly longer random snippets out of templates
        val blocks = listOf(
            "if(true){\nX\n}",
            "if(flag){\nX\n}else{\nY\n}",
            "try{\nX\n}catch(e){\nY\n}\nfinally{\nZ\n}",
            "fun f(){\nX\n}",
        )
        val leaves = listOf(
            "doIt(A,B ,C)",
            "obj.m().n( a , b).k()",
            "val arr = [\n1,\n2,\n3\n]",
            "println(\"keep ( raw )\") // cmt",
        )
        val cfg1 = LyngFormatConfig()
        val cfg2 = LyngFormatConfig(applySpacing = true)
        repeat(15) { n ->
            val t1 = blocks[n % blocks.size]
            val t2 = blocks[(n + 1) % blocks.size]
            val l1 = leaves[n % leaves.size]
            val l2 = leaves[(n + 1) % leaves.size]
            val l3 = leaves[(n + 2) % leaves.size]
            var doc = (t1 + "\n" + t2)
            doc = doc.replace("X", l1).replace("Y", l2).replace("Z", l3)
            val r1 = LyngFormatter.reindent(doc, cfg1)
            val r2 = LyngFormatter.reindent(r1, cfg1)
            assertEquals(r1, r2)
            val f1 = LyngFormatter.format(doc, cfg2)
            val f2 = LyngFormatter.format(f1, cfg2)
            assertEquals(f1, f2)
        }
    }

    @Test
    fun propertyBasedIdempotencyLargeRandom() {
        // Generate larger random snippets from building blocks and verify idempotency
        val blocks = listOf(
            "if(true){\nX\n}",
            "if(flag){\nX\n}else if(maybe){\nY\n}else{\nZ\n}",
            "try{\nX\n}catch(e){\nY\n}\nfinally{\nZ\n}",
            "fun f(){\nX\n}",
            "val arr = [\nX,\nY,\nZ\n]",
            "doIt(A,B ,C)\nobj.m().n( a , b).k()\n",
        )
        val leaves = listOf(
            "println(1)",
            "op()",
            "compute(a, b, c)",
            "other()",
            "arr[i][j]",
        )
        val cfgIndentOnly = LyngFormatConfig()
        val cfgSpacing = LyngFormatConfig(applySpacing = true)

        fun assemble(n: Int): String {
            val sb = StringBuilder()
            for (i in 0 until n) {
                val b = blocks[(i * 7 + n) % blocks.size]
                val x = leaves[(i + 0) % leaves.size]
                val y = leaves[(i + 1) % leaves.size]
                val z = leaves[(i + 2) % leaves.size]
                sb.append(b.replace("X", x).replace("Y", y).replace("Z", z)).append('\n')
            }
            return sb.toString().trimEnd()
        }

        repeat(10) { n ->
            val doc = assemble(8 + (n % 5))
            val once1 = LyngFormatter.reindent(doc, cfgIndentOnly)
            val twice1 = LyngFormatter.reindent(once1, cfgIndentOnly)
            assertEquals(once1, twice1)
            val once2 = LyngFormatter.format(doc, cfgSpacing)
            val twice2 = LyngFormatter.format(once2, cfgSpacing)
            assertEquals(once2, twice2)
        }
    }

    @Test
    fun comments_and_strings_untouched() {
        val src = """
            // header
            fun demo() {
            val s = "( a ) , [ b ]" // keep as-is
            if(true){
            println(s)
            }
            }
        """.trimIndent()

        val expected = """
            // header
            fun demo() {
                val s = "( a ) , [ b ]" // keep as-is
                if(true){
                    println(s)
                }
            }
        """.trimIndent()

        // Use full format with spacing disabled to guarantee strings/comments remain untouched
        val out = LyngFormatter.format(src, LyngFormatConfig(applySpacing = false))
        assertEquals(expected, out)
        // Idempotent
        assertEquals(expected, LyngFormatter.format(out, LyngFormatConfig(applySpacing = false)))
    }

    @Test
    fun reindent_nestedBlocks_andElse() {
        val src = """
            if(true){
            if (cond) {
            doIt()
            }
            else {
            other()
            }
            }
        """.trimIndent()

        val expected = """
            if (true) {
                if (cond) {
                    doIt()
                }
                else {
                    other()
                }
            }
        """.trimIndent()

        val out = LyngFormatter.format(src, LyngFormatConfig(applySpacing = true))
        assertEquals(expected, out)
        // Idempotent
        assertEquals(expected, LyngFormatter.format(out, LyngFormatConfig(applySpacing = true)))
    }

    @Test
    fun continuationIndent_parentheses() {
        val src = """
            fun call() {
            doSomething(
            a,
            b,
            c
            )
            }
        """.trimIndent()

        val expected = """
            fun call() {
                doSomething(
                    a,
                    b,
                    c
                )
            }
        """.trimIndent()

        val cfg = LyngFormatConfig(indentSize = 4, continuationIndentSize = 4)
        val out = LyngFormatter.reindent(src, cfg)
        assertEquals(expected, out)
        // Idempotent
        assertEquals(expected, LyngFormatter.reindent(out, cfg))
    }
    @Test
    fun realexample1() {
        val src = """
            {
                if( f.isDirectory() )
                name += "/"
                if( condition ) 
                callIfTrue()
                else
                calliffalse()
            }
        """.trimIndent()

        val expected = """
            {
                if( f.isDirectory() )
                    name += "/"
                if( condition ) 
                    callIfTrue()
                else
                    calliffalse()
            }
        """.trimIndent()

        val cfg = LyngFormatConfig(indentSize = 4, continuationIndentSize = 4)
        val out = LyngFormatter.reindent(src, cfg)
        assertEquals(expected, out)
        // Idempotent
        assertEquals(expected, LyngFormatter.reindent(out, cfg))
    }

    @Test
    fun stringLiterals_areNotNormalized() {
        val src = """
            val s = "a=b"
            val s2 = "a + b"
            val s3 = "a  -  b"
            val s4 = "a,b"
            val s5 = "if(x){}"
        """.trimIndent()

        val cfg = LyngFormatConfig(applySpacing = true)
        val formatted = LyngFormatter.format(src, cfg)

        assertEquals(src, formatted, "String literals should not be changed by space normalization")
    }

    @Test
    fun mixedCodeAndStrings_normalization() {
        val src = "val x=1+\"a+b\"+2"
        val expected = "val x = 1 + \"a+b\" + 2"

        val cfg = LyngFormatConfig(applySpacing = true)
        val formatted = LyngFormatter.format(src, cfg)

        assertEquals(expected, formatted)
    }

    @Test
    fun reindent_ignoresBracesInStrings() {
        val src = """
            fun test() {
                val s = "{"
                val s2 = "}"
            }
        """.trimIndent()

        val cfg = LyngFormatConfig(indentSize = 4)
        val formatted = LyngFormatter.reindent(src, cfg)

        // If it fails, s2 will be indented differently
        assertEquals(src, formatted, "Braces in strings should not affect indentation")
    }

    @Test
    fun blockComments_spacing_robustness() {
        val src = "val x=1/*a=b*/+2"
        val expected = "val x = 1/*a=b*/ + 2"

        val cfg = LyngFormatConfig(applySpacing = true)
        val formatted = LyngFormatter.format(src, cfg)

        assertEquals(expected, formatted)
    }

    @Test
    fun privateSet_indentation() {
        val src = """
            class A {
                var x = 1
                private set
            }
        """.trimIndent()

        // What we expect: private set should be indented relative to the property
        val expected = """
            class A {
                var x = 1
                    private set
            }
        """.trimIndent()

        val cfg = LyngFormatConfig(indentSize = 4, continuationIndentSize = 4)
        val out = LyngFormatter.reindent(src, cfg)
        assertEquals(expected, out)
    }

    @Test
    fun propertyAccessors_indentation() {
        val src = """
            class A {
                var x
                get() = 1
                private set(v) {
                _x = v
                }
            }
        """.trimIndent()

        val expected = """
            class A {
                var x
                    get() = 1
                    private set(v) {
                        _x = v
                    }
            }
        """.trimIndent()

        val cfg = LyngFormatConfig(indentSize = 4, continuationIndentSize = 4)
        val out = LyngFormatter.reindent(src, cfg)
        assertEquals(expected, out)
    }

    @Test
    fun nestedAwaitingIndentation() {
        val src = """
            if (cond)
            if (other)
            fun f() {
            stmt
            }
        """.trimIndent()

        val expected = """
            if (cond)
                if (other)
                    fun f() {
                        stmt
                    }
        """.trimIndent()

        val cfg = LyngFormatConfig(indentSize = 4, continuationIndentSize = 4)
        val out = LyngFormatter.reindent(src, cfg)
        assertEquals(expected, out)
    }

    @Test
    fun propertyAccessor_followedByMethod() {
        val src = """
            var state: BarRequestState get() = getState()
                set(value) = setState(value)

            fun save() { cell.value = this }
        """.trimIndent()

        val expected = """
            var state: BarRequestState get() = getState()
                set(value) = setState(value)

            fun save() { cell.value = this }
        """.trimIndent()

        val cfg = LyngFormatConfig(indentSize = 4, continuationIndentSize = 4)
        val out = LyngFormatter.reindent(src, cfg)
        assertEquals(expected, out)
    }

    @Test
    fun propertyAccessor_dangling() {
        val src1 = """
            var x
                get()
                = 1
        """.trimIndent()
        val expected1 = """
            var x
                get()
                    = 1
        """.trimIndent()
        assertEquals(expected1, LyngFormatter.reindent(src1, LyngFormatConfig(indentSize = 4)))

        val src2 = """
            var x
                get() =
                1
        """.trimIndent()
        val expected2 = """
            var x
                get() =
                    1
        """.trimIndent()
        assertEquals(expected2, LyngFormatter.reindent(src2, LyngFormatConfig(indentSize = 4)))
    }
}
