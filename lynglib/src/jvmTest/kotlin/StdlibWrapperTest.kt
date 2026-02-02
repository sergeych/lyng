import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.Script
import kotlin.test.Test
import kotlin.test.assertNotNull

class StdlibWrapperTest {
    @Test
    fun testStdlibExtensionWrapperPresent() = runTest {
        val scope = Script.newScope()
        val rec = scope.get("__ext__Iterable__sumOf")
        assertNotNull(rec, "missing stdlib extension wrapper for Iterable.sumOf")
    }
}
