import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.Scope
import net.sergeych.lyng.obj.ObjVoid
import java.nio.file.Files
import java.nio.file.Files.readAllLines
import java.nio.file.Paths
import kotlin.io.path.absolutePathString
import kotlin.io.path.extension
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
    val expectedError: String? = null,
    val bookMode: Boolean = false
) {
    val sourceLines by lazy { code.lines() }

    val fileNamePart by lazy {
        Paths.get(fileName).fileName.toString()
    }

    override fun toString(): String {
        val absPath = Paths.get(fileName).absolutePathString()
        return "DocTest: $absPath:${line + 1}"
    }

    val detailedString by lazy {
        val codeWithLines = sourceLines.withIndex().map { (i, s) -> "${i + line + 1}: $s" }.joinToString("\n")
        var result = "$this\n$codeWithLines\n"
        if( !bookMode) {
            if (expectedOutput.isNotBlank())
                result += "--------expected output--------\n$expectedOutput\n"

            "$result-----expected return value-----\n$expectedResult"
        }
        else result
    }
}

fun parseDocTests(fileName: String, bookMode: Boolean = false): Flow<DocTest> = flow {
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
                        if (bookMode) {
                            emit(
                                DocTest(
                                    fileName, startIndex,
                                    block.joinToString("\n"),
                                    "",
                                    "",
                                    null,
                                    bookMode = true
                                )
                            )
                        }
//                        println(block.joinToString("\n") { "${startIndex + ii++}: $it" })
                        val outStart = block.indexOfFirst { it.startsWith(">>>") }
                        if (outStart < 0) {
                            // println("No output at block from line ${startIndex+1}")
                        } else {
                            var isValid = true
                            val result = mutableListOf<String>()

                            // remove empty trails:
                            while( block.last().isEmpty() ) block.removeLast()

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

suspend fun DocTest.test(scope: Scope = Scope()) {
    val collectedOutput = StringBuilder()
    val currentTest = this
    scope.apply {
        addFn("println") {
            if( bookMode ) {
                println("${currentTest.fileNamePart}:${currentTest.line}> ${args.joinToString(" "){it.asStr.value}}")
            }
            else {
                for ((i, a) in args.withIndex()) {
                    if (i > 0) collectedOutput.append(' '); collectedOutput.append(a.asStr.value)
                    collectedOutput.append('\n')
                }
            }
            ObjVoid
        }
    }
    var error: Throwable? = null
    val result = try {
        scope.eval(code)
    } catch (e: Throwable) {
        error = e
        null
    }?.inspect()?.replace(Regex("@\\d+"), "@...")

    if (bookMode) {
        if (error != null) {
            println("Sample failed: ${this.detailedString}")
            fail("book sample failed", error)
        }
    } else {
        if (error != null || expectedOutput != collectedOutput.toString() ||
            expectedResult != result
        ) {
            System.err.println("\nfailed: ${this.detailedString}")
        }
        error?.let {
            fail(it.message, it)
        }
        assertEquals(expectedOutput, collectedOutput.toString(), "script output do not match")
        assertEquals(expectedResult, result.toString(), "script result does not match")
        //    println("OK: $this")
    }
}

suspend fun runDocTests(fileName: String, bookMode: Boolean = false) {
    val bookScope = Scope()
    var count = 0
    parseDocTests(fileName, bookMode).collect { dt ->
        if (bookMode) dt.test(bookScope)
        else dt.test()
        count++
    }
    print("completed mdtest: $fileName; ")
    if (bookMode)
        println("fragments processed: $count")
    else
        println("tests passed: $count")
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

    @Test
    fun testFromList() = runTest {
        runDocTests("../docs/List.md")
    }

    @Test
    fun testFromRange() = runTest {
        runDocTests("../docs/Range.md")
    }

    @Test
    fun testSet() = runTest {
        runDocTests("../docs/Set.md")
    }

    @Test
    fun testMap() = runTest {
        runDocTests("../docs/Map.md")
    }

    @Test
    fun testBuffer() = runTest {
        runDocTests("../docs/Buffer.md")
    }

    @Test
    fun testSampleBooks() = runTest {
        for (bt in Files.list(Paths.get("../docs/samples")).toList()) {
            if (bt.extension == "md") {
                runDocTests(bt.toString(), bookMode = true)
            }
        }
    }

    @Test
    fun testArgumentBooks() = runTest {
        runDocTests("../docs/declaring_arguments.md")
    }

    @Test
    fun testExceptionsBooks() = runTest {
        runDocTests("../docs/exceptions_handling.md")
    }

    @Test
    fun testTimeBooks() = runBlocking {
        runDocTests("../docs/time.md")
    }

    @Test
    fun testParallelismBook() = runBlocking {
        runDocTests("../docs/parallelism.md")
    }

}