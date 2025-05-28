import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.test.runTest
import net.sergeych.ling.Context
import net.sergeych.ling.ObjVoid
import java.nio.file.Files.readAllLines
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

fun leftMargin(s: String): Int {
    var cnt = 0
    for (c in s) {
        when (c) {
            ' ' -> cnt++
            '\t' -> cnt = (cnt / 4.0 + 0.9).toInt() * 4
            else -> break
        }
    }
    return cnt
}

data class DocTest(
    val fileName: String,
    val line: Int,
    val code: String,
    val expectedOutput: String,
    val expectedResult: String,
    val expectedError: String? = null
) {
    val sourceLines by lazy { code.lines() }

    override fun toString(): String {
        return "DocTest:$fileName:${line + 1}..${line + sourceLines.size}"
    }

    val detailedString by lazy {
        val codeWithLines = sourceLines.withIndex().map { (i, s) -> "${i + line + 1}: $s" }.joinToString("\n")
        var result = "$this\n$codeWithLines\n"
        if (expectedOutput.isNotBlank())
            result += "--------expected output--------\n$expectedOutput\n"

        "$result-----expected return value-----\n$expectedResult"
    }
}

fun parseDocTests(fileName: String): Flow<DocTest> = flow {
    val book = readAllLines(Paths.get(fileName))
    var startOffset = 0
    val block = mutableListOf<String>()
    var startIndex = 0
    for ((index, l) in book.withIndex()) {
        val off = leftMargin(l)
        when {
            off < startOffset && startOffset != 0 -> {
                if (l.isBlank()) {
                    continue
                }
                // end of block or just text:
                if (block.isNotEmpty()) {
                    // check/create block
                    // 2 lines min
                    if (block.size > 1) {
                        // remove prefix
                        for ((i, s) in block.withIndex()) {
                            var x = s
                            // could be tabs :(
                            val initial = leftMargin(x)
                            do {
                                x = x.drop(1)
                            } while (initial - leftMargin(x) != startOffset)
                            block[i] = x
                        }
//                        println(block.joinToString("\n") { "${startIndex + ii++}: $it" })
                        val outStart = block.indexOfFirst { it.startsWith(">>>") }
                        if (outStart < 0) {
                            // println("No output at block from line ${startIndex+1}")
                        } else {
                            var isValid = true
                            val result = mutableListOf<String>()
                            while (block.size > outStart) {
                                val line = block.removeAt(outStart)
                                if (!line.startsWith(">>> ")) {
                                    println("invalid output line, must start with '>>> ', block from ${startIndex + 1}: $line")
                                    isValid = false
                                    break
                                }
                                result.add(line.drop(4))
                            }
                            if (isValid) {
                                emit(
                                    DocTest(
                                        fileName, startIndex,
                                        block.joinToString("\n"),
                                        if (result.size > 1)
                                            result.dropLast(1).joinToString("") { it + "\n" }
                                        else "",
                                        result.last()
                                    )
                                )
                            }
                        }
                        // last line '>>>'
                    }
                    block.clear()
                    startOffset = 0
                }
            }

            off != 0 && startOffset == 0 -> {
                // start
                block.clear()
                startIndex = index
                block.add(l)
                startOffset = off
            }

            off != 0 -> {
                block.add(l)
            }

            off == 0 && startOffset == 0 -> {
                // skip
            }

            else -> {
                throw RuntimeException("Unexpected line: ($off/$startOffset) $l")
            }
        }
    }
}
    .flowOn(Dispatchers.IO)

suspend fun DocTest.test() {
    val collectedOutput = StringBuilder()
    val context = Context().apply {
        addFn("println") {
            for ((i, a) in args.withIndex()) {
                if (i > 0) collectedOutput.append(' '); collectedOutput.append(a)
                collectedOutput.append('\n')
            }
            ObjVoid
        }
    }
    var error: Throwable? = null
    val result = try {
        context.eval(code)
    } catch (e: Throwable) {
        error = e
        null
    }?.toString()?.replace(Regex("@\\d+"), "@...")

    if (error != null || expectedOutput != collectedOutput.toString() ||
        expectedResult != result
    ) {
        println("Test failed: ${this.detailedString}")
    }
    error?.let { fail(it.toString()) }
    assertEquals(expectedOutput, collectedOutput.toString(), "script output do not match")
    assertEquals(expectedResult, result.toString(), "script result does not match")
    //    println("OK: $this")
}

suspend fun runDocTests(fileName: String) {
    parseDocTests(fileName).collect { dt ->
        dt.test()
    }

}

class BookTest {

    @Test
    fun testsFromTutorial() = runTest {
        runDocTests("../docs/tutorial.md")
    }

    @Test
    fun testsFromMath() = runTest {
        runDocTests("../docs/math.md")
    }

    @Test
    fun testsFromAdvanced() = runTest {
        runDocTests("../docs/advanced_topics.md")
    }

    @Test
    fun testsFromOOP() = runTest {
        runDocTests("../docs/OOP.md")
    }

    @Test
    fun testFromReal() = runTest {
        runDocTests("../docs/Real.md")
    }

}