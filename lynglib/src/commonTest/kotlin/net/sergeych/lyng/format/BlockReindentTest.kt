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
 * Tests for block detection and partial reindent in Lyng formatter
 */
package net.sergeych.lyng.format

import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class BlockReindentTest {
    @Test
    fun findMatchingOpen_basic() {
        val text = """
            fun f() {
                {
                    1
                }
            }
        """.trimIndent()
        val close = text.lastIndexOf('}')
        val open = BraceUtils.findMatchingOpenBrace(text, close)
        assertNotNull(open)
        // The char at open must be '{'
        assertEquals('{', text[open!!])
    }

    @Test
    fun findEnclosingBlock_rangeIncludesWholeBraceLines() {
        val text = """
            fun f() {
              {
               1
              }
            }
        """.trimIndent() + "\n" // final newline to emulate editor
        val close = text.indexOfLast { it == '}' }
        val range = BraceUtils.findEnclosingBlockRange(text, close, includeTrailingNewline = true)
        assertNotNull(range)
        // The range must start at the line start of the matching '{' and end at or after the newline after '}'
        val start = range!!.first
        val end = range.last + 1
        val startLinePrefix = text.substring(BraceUtils.lineStart(text, start), start)
        // start at column 0 of the line
        assertEquals(0, startLinePrefix.length)
        // end should be at a line boundary
        val endsAtNl = (end == text.length) || text.getOrNull(end - 1) == '\n'
        assertEquals(true, endsAtNl)
    }

    @Test
    fun partialReindent_fixesInnerIndent_preservesBaseIndent() {
        val original = """
            fun test21() {
            {  // inner block wrongly formatted
             21
              }
            }
        """.trimIndent() + "\n"

        val commentPos = original.indexOf("// inner block")
        val close = original.indexOf('}', startIndex = commentPos)
        val range = BraceUtils.findEnclosingBlockRange(original, close, includeTrailingNewline = true)!!

        val cfg = LyngFormatConfig(indentSize = 4, continuationIndentSize = 8, useTabs = false)
        val updated = LyngFormatter.reindentRange(original, range, cfg, preserveBaseIndent = true)

        // Validate shape: first line starts with '{', second line is indented '21', third line is '}'
        val slice = updated.substring(range.first, min(updated.length, range.last + 1))
        assertEquals("""
            fun test21() {
                {  // inner block wrongly formatted
                    21
                }
            }
        """.trimIndent()+"\n", updated)

        val lines = slice.removeSuffix("\n").lines()
    }

    @Test
    fun nestedBlocks_partialReindent_innerOnly() {
        val original = """
            fun outer() {
                {
                 {
                  1
                   }
                }
            }
        """.trimIndent() + "\n"

        // Target the closing brace of the INNERMOST block (the first closing brace in the snippet)
        val innerClose = original.indexOf('}')
        val range = BraceUtils.findEnclosingBlockRange(original, innerClose, includeTrailingNewline = true)!!

        val cfg = LyngFormatConfig(indentSize = 4, continuationIndentSize = 8, useTabs = false)
        val updated = LyngFormatter.reindentRange(original, range, cfg, preserveBaseIndent = true)

        val slice = updated.substring(range.first, min(updated.length, range.last + 1))
        val lines = slice.removeSuffix("\n").lines()
        val baseLen = lines.first().takeWhile { it == ' ' || it == '\t' }.length
        val l0 = lines[0].drop(baseLen)
        val l1 = lines[1].drop(baseLen)
        val l2 = lines[2].drop(baseLen)
        // Expect properly shaped inner block
        assertEquals(true, l0.startsWith("{"))
        assertEquals("    1", l1)
        assertEquals("}", l2)
    }

    @Test
    fun blockWithInlineComments_detectAndReindent() {
        val original = """
            fun cmt() {
              { // open
               21 // body
              } // close
            }
        """.trimIndent() + "\n"
        val close = original.indexOf("} // close")
        val range = BraceUtils.findEnclosingBlockRange(original, close, includeTrailingNewline = true)!!
        val cfg = LyngFormatConfig(indentSize = 2, continuationIndentSize = 4, useTabs = false)
        val updated = LyngFormatter.reindentRange(original, range, cfg, preserveBaseIndent = true)
        val slice = updated.substring(range.first, min(updated.length, range.last + 1))
        val lines = slice.removeSuffix("\n").lines()
        val baseLen = lines.first().takeWhile { it == ' ' || it == '\t' }.length
        val l0 = lines[0].drop(baseLen)
        val l1 = lines[1].drop(baseLen)
        val l2 = lines[2].drop(baseLen)
        assertEquals(true, l0.startsWith("{ // open"))
        assertEquals("  21 // body", l1) // 2-space indent
        assertEquals(true, l2.startsWith("} // close"))
    }

    @Test
    fun emptyBlock_isNormalized() {
        val original = """
            fun e() {
            {   }
            }
        """.trimIndent() + "\n"
        val close = original.indexOf('}')
        val range = BraceUtils.findEnclosingBlockRange(original, close, includeTrailingNewline = true)!!
        val cfg = LyngFormatConfig(indentSize = 4, continuationIndentSize = 8, useTabs = false)
        val updated = LyngFormatter.reindentRange(original, range, cfg, preserveBaseIndent = true)
        val slice = updated.substring(range.first, range.last + 1)
        val lines = slice.removeSuffix("\n").lines()
        val baseLen = lines.first().takeWhile { it == ' ' || it == '\t' }.length
        // Drop base indent and collapse whitespace; expect only braces remain in order
        val innerText = lines.joinToString("\n") { it.drop(baseLen) }.trimEnd()
        val collapsed = innerText.replace(" ", "").replace("\t", "").replace("\n", "")
        assertEquals("{}", collapsed)
    }

    @Test
    fun tabBaseIndent_preserved() {
        val original = "\t\t{\n\t  21\n\t}\n" // tabs for base indent, bad body indent
        val close = original.lastIndexOf('}')
        val range = BraceUtils.findEnclosingBlockRange(original, close, includeTrailingNewline = true)!!
        val cfg = LyngFormatConfig(indentSize = 4, continuationIndentSize = 8, useTabs = true)
        val updated = LyngFormatter.reindentRange(original, range, cfg, preserveBaseIndent = true)
        // New policy: resulting code must not contain tabs
        kotlin.test.assertTrue(!updated.contains('\t'))
    }

    @Test
    fun noTrailingNewline_afterClose_isHandled() {
        val original = """
            fun f() {
            {
            21
            }
            }""".trimIndent() // EOF right after '}'
        val close = original.lastIndexOf('}')
        val range = BraceUtils.findEnclosingBlockRange(original, close, includeTrailingNewline = true)!!
        val cfg = LyngFormatConfig(indentSize = 2, continuationIndentSize = 4, useTabs = false)
        val updated = LyngFormatter.reindentRange(original, range, cfg, preserveBaseIndent = true)
        assertEquals(true, updated.isNotEmpty())
    }

    @Test
    fun bracketContinuation_firstElementIndented() {
        val original = """
            val arr = [
            1,
            2
            ]
        """.trimIndent()
        val cfg = LyngFormatConfig(indentSize = 2, continuationIndentSize = 4, useTabs = false)
        val updated = LyngFormatter.reindent(original, cfg)
        val allLines = updated.lines()
        val lines = allLines.dropLastWhile { it.isBlank() }
        // Expect first element line to be continuation-indented (4 spaces)
        assertEquals("    1,", lines[1])
        assertEquals("    2", lines[2])
        // Closing bracket should align with 'val arr = [' line
        assertEquals(
            lines[0].takeWhile { it == ' ' || it == '\t' } + "]",
            lines[3]
        )
    }

    @Test
    fun parenContinuation_multilineCondition() {
        val original = """
            if (
            a &&
            b ||
            c
            )
            {
            1
            }
        """.trimIndent()
        val cfg = LyngFormatConfig(indentSize = 2, continuationIndentSize = 4, useTabs = false)
        val updated = LyngFormatter.reindent(original, cfg)
        val lines = updated.lines()
        // Lines inside parentheses get continuation indent (4 spaces)
        assertEquals("    a &&", lines[1])
        assertEquals("    b ||", lines[2])
        assertEquals("    c", lines[3])
        // Closing paren line should not get continuation
        assertEquals(")", lines[4].trimStart())
    }

    @Test
    fun chainedCalls_withParens_continuationIndent() {
        val original = """
            val x = service
            .call(
            a,
            b,
            c
            )
        """.trimIndent()
        val cfg = LyngFormatConfig(indentSize = 2, continuationIndentSize = 4, useTabs = false)
        val updated = LyngFormatter.reindent(original, cfg)
        val lines = updated.lines()
        // inside call parentheses lines should have continuation indent (4 spaces)
        assertEquals("    a,", lines[2])
        assertEquals("    b,", lines[3])
        assertEquals("    c", lines[4])
        // closing ')' line should not have continuation
        assertEquals(")", lines[5].trimStart())
    }

    @Test
    fun mixedTabsSpaces_baseIndent_preserved() {
        // base indent has one tab then two spaces; after normalization no tabs should remain
        val original = "\t  [\n1,\n2\n]" // no trailing newline
        val cfg = LyngFormatConfig(indentSize = 2, continuationIndentSize = 4, useTabs = false)
        val updated = LyngFormatter.reindent(original, cfg)
        kotlin.test.assertTrue(!updated.contains('\t'))
    }

    @Test
    fun deepParentheses_and_chainedCalls() {
        val original = """
            if (
                a &&
                (b || c(
                    x,
                    y
                )) && service
                .call(
                    p,
                    q
                )
            )
            {
            1
            }
        """.trimIndent()
        val cfg = LyngFormatConfig(indentSize = 2, continuationIndentSize = 4, useTabs = false)
        val updated = LyngFormatter.reindent(original, cfg)
        val lines = updated.lines()
        // Inside top-level parens continuation should apply (4 spaces)
        assertEquals("    a &&", lines[1])
        // Nested parens of c( x, y ) apply continuation; current rules accumulate with outer parens
        // resulting in deeper indent for nested arguments
        assertEquals("            x,", lines[3])
        assertEquals("            y", lines[4])
        // Chained call `.call(` lines inside parentheses are continuation-indented
        assertEquals("    .call(", lines[6].trimEnd())
        assertEquals("        p,", lines[7])
        assertEquals("        q", lines[8])
        // Closing paren line should not get continuation
        assertEquals(")", lines[10].trimStart())
        // Block body is indented by one level
        assertEquals("  1", lines[12])
    }

    @Test
    fun eof_edge_without_trailing_newline_in_block_and_arrays() {
        val original = """
            fun g() {
            [
            1,
            2
            ]
            }
        """.trimIndent().removeSuffix("\n") // ensure no final newline
        val cfg = LyngFormatConfig(indentSize = 2, continuationIndentSize = 4, useTabs = false)
        val updated = LyngFormatter.reindent(original, cfg)
        val lines = updated.lines()
        // First element line should have continuation indent (4 spaces) in addition to base
        assertEquals("    1,", lines[2])
        assertEquals("    2", lines[3])
        // Closing bracket aligns with base (no continuation)
        assertEquals("]", lines[4].trimStart())
        // File ends without extra newline
        assertEquals(false, updated.endsWith("\n"))
    }

    @Test
    fun partialPaste_preservesBaseIndent_forAllLines() {
        // Simulate a paste at the start of an indented line (caret inside leading whitespace)
        val before = """
            fun pasteHere() {
                
            }
        """.trimIndent() + "\n"
        val caretLineStart = before.indexOf("\n", before.indexOf("pasteHere")) + 1
        // base indent on the empty line inside the block is 4 spaces
        val caretOffset = caretLineStart + 4
        val paste = """
        if (x) {
        1
         }
        """.trimIndent()

        // Build the document text as if pasted as-is first
        val afterPaste = StringBuilder(before).insert(caretOffset, paste).toString()
        val insertedRange = caretOffset until (caretOffset + paste.length)

        val cfg = LyngFormatConfig(indentSize = 4, continuationIndentSize = 8, useTabs = false)
        val updated = LyngFormatter.reindentRange(afterPaste, insertedRange, cfg, preserveBaseIndent = true)

        // Policy: spaces-only, structure preserved. Check shape instead of exact whitespace.
        kotlin.test.assertTrue(!updated.contains('\t'))
        val lines = updated.lines().dropLastWhile { it.isBlank() }
        // Keep function header and footer
        assertEquals("fun pasteHere() {", lines[0])
        assertEquals("}", lines.last())
        // Inside the block, one of two styles is acceptable:
        // 1) compact brace on condition line
        // 2) condition then brace on next line
        val blockLines = lines.subList(1, lines.size - 1)
        val compact = blockLines.firstOrNull()?.trim() == "if (x) {"
        if (compact) {
            // Body should be indented by at least one level (4 spaces)
            kotlin.test.assertTrue(blockLines.getOrNull(1)?.startsWith("        ") == true ||
                    blockLines.getOrNull(1)?.startsWith("    ") == true)
            // Closing brace should appear in one of subsequent lines
            kotlin.test.assertTrue(blockLines.drop(1).any { it.trim() == "}" })
        } else {
            assertEquals("if (x)", blockLines.getOrNull(0))
            assertEquals("{", blockLines.getOrNull(1))
            kotlin.test.assertTrue(blockLines.getOrNull(2)?.startsWith("    ") == true)
            kotlin.test.assertTrue(blockLines.drop(2).any { it.trim() == "}" })
        }
    }

    @Test
    fun partialPaste_midLine_noBasePrefix() {
        // Paste occurs mid-line (after non-whitespace): base indent must not be applied
        val before = "val a = 1\n"
        val caretOffset = before.indexOf('1') + 1 // after the '1', mid-line
        val paste = "\n{\n1\n}\n".trimIndent()
        val afterPaste = StringBuilder(before).insert(caretOffset, paste).toString()
        val insertedRange = caretOffset until (caretOffset + paste.length)
        val cfg = LyngFormatConfig(indentSize = 2, continuationIndentSize = 4, useTabs = false)
        val updated = LyngFormatter.reindentRange(afterPaste, insertedRange, cfg, preserveBaseIndent = true)
        // Find the opening brace position and verify structure around it in the updated text
        val openIdx = updated.indexOf('{', startIndex = insertedRange.first)
        kotlin.test.assertTrue(openIdx >= 0)
        // Next line should be indented body line with 2 spaces then '1'
        val afterOpenNl = updated.indexOf('\n', openIdx) + 1
        val bodyLineEnd = updated.indexOf('\n', afterOpenNl).let { if (it < 0) updated.length else it }
        val bodyLine = updated.substring(afterOpenNl, bodyLineEnd)
        assertEquals("  1", bodyLine)
        // Closing brace should appear on its own line (no leading spaces)
        val closeLineStart = bodyLineEnd + 1
        val closeLineEnd = updated.indexOf('\n', closeLineStart).let { if (it < 0) updated.length else it }
        val closeLine = updated.substring(closeLineStart, closeLineEnd)
        assertEquals("}", closeLine)
    }

    @Test
    fun partialPaste_replacingPartOfLeadingWhitespace_usesRemainingAsBase() {
        // The selection replaces part of the leading whitespace; base should be the whitespace before start
        val before = """
            fun g() {
                
            }
        """.trimIndent() + "\n"
        val blankLineStart = before.indexOf("\n", before.indexOf("g()")) + 1
        before.substring(blankLineStart, before.indexOf('\n', blankLineStart))
        // line currently has 4 spaces; select and replace the last 2 spaces
        val selectionStart = blankLineStart + 2
        val selectionEnd = blankLineStart + 4
        val paste = "{\n1\n}\n"
        val afterPaste = before.substring(0, selectionStart) + paste + before.substring(selectionEnd)
        val insertedRange = selectionStart until (selectionStart + paste.length)
        val cfg = LyngFormatConfig(indentSize = 2, continuationIndentSize = 4, useTabs = false)
        val updated = LyngFormatter.reindentRange(afterPaste, insertedRange, cfg, preserveBaseIndent = true)
        // Spaces-only and structural validation
        kotlin.test.assertTrue(!updated.contains('\t'))
        val all2 = updated.lines()
        val lines = all2.dropLastWhile { it.isBlank() }
        assertEquals("fun g() {", lines.first())
        assertEquals("}", lines.last())
        // Find the inner block lines and check indentation levels exist (spaces)
        val innerStart = lines.indexOfFirst { it.trimStart().startsWith("{") }
        kotlin.test.assertTrue(innerStart > 0)
        kotlin.test.assertTrue(lines.getOrNull(innerStart + 1)?.trimStart()?.startsWith("1") == true)
        kotlin.test.assertEquals("}", lines.getOrNull(innerStart + 2)?.trim())
    }
    @Test
    fun reindentRange_doubledIndentationFix() {
        val src = """
            class X {
                fun funA(): String {
                    "a"
                }
                fun runB() {
                      }
            }
        """.trimIndent()

        // The range for runB should be from the start of 'fun runB' line to the end of '}' line.
        val startOfRunB = src.indexOf("    fun runB")
        val endOfRunB = src.indexOf("          }") + "          }".length
        val range = startOfRunB until endOfRunB

        val cfg = LyngFormatConfig(indentSize = 4)
        val updated = LyngFormatter.reindentRange(src, range, cfg, preserveBaseIndent = true)

        val expected = """
            class X {
                fun funA(): String {
                    "a"
                }
                fun runB() {
                }
            }
        """.trimIndent()

        assertEquals(expected, updated)
    }

}
