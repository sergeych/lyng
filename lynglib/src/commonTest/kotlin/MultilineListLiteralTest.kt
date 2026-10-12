import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.Script
import kotlin.test.*

class MultilineListLiteralTest {
    @Test fun finalNewlineCommentsAndTrailingCommaAreWhitespace() = runTest {
        val scope = Script.newScope()
        for (source in listOf(
            "[\n \"first\",\n \"second\"\n]",
            "[\n \"first\",\n \"second\",\n]",
            "[\n // before\n \"first\", /* between */\n \"second\" // after\n]",
        )) assertEquals("[first,second]", scope.eval(source).toString(scope).value)
    }
    @Test fun nestedAndEmptyMultilineListsAndExpressionsRemainValid() = runTest {
        val scope = Script.newScope()
        assertEquals("[]", scope.eval("[\n]").toString(scope).value)
        assertEquals("[[1,2],[3,4]]", scope.eval("[\n [1,2],\n [3,4]\n]").toString(scope).value)
        assertEquals("[3,7]", scope.eval("[\n 1+2,\n 3+4\n]").toString(scope).value)
        assertFails { scope.eval("[\n @\n]") }
    }
}
