import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.runBlocking
import net.sergeych.lyng.*
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
        val pm = InlineSourcesPacman(
            Pacman.emptyAllowAll, listOf(
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

}