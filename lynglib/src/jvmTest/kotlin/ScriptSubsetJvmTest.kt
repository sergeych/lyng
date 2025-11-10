import kotlinx.coroutines.runBlocking
import net.sergeych.lyng.PerfFlags
import net.sergeych.lyng.Scope
import net.sergeych.lyng.obj.ObjInt
import net.sergeych.lyng.obj.ObjList
import kotlin.test.Test
import kotlin.test.assertEquals

class ScriptSubsetJvmTest {
    private suspend fun evalInt(code: String): Long = (Scope().eval(code) as ObjInt).value
    private suspend fun evalList(code: String): List<Any?> = (Scope().eval(code) as ObjList).list.map { (it as? ObjInt)?.value ?: it }

    @Test
    fun binarySearchBasics_jvm_only() = runBlocking {
        val code = """
            val coll = [1,2,3,4,5]
            coll.binarySearch(3)
        """.trimIndent()
        // OFF
        PerfFlags.RVAL_FASTPATH = false
        val rOff = evalInt(code)
        // ON
        PerfFlags.RVAL_FASTPATH = true
        val rOn = evalInt(code)
        assertEquals(2L, rOff)
        assertEquals(2L, rOn)
        PerfFlags.RVAL_FASTPATH = false
    }

    @Test
    fun optionalChainingIndexField_jvm_only() = runBlocking {
        val code = """
            val a = null
            val r1 = a?.x
            val lst = [1,2,3]
            val r2 = lst[1]
            r2
        """.trimIndent()
        PerfFlags.RVAL_FASTPATH = false
        val off = evalInt(code)
        PerfFlags.RVAL_FASTPATH = true
        val on = evalInt(code)
        assertEquals(2L, off)
        assertEquals(2L, on)
        PerfFlags.RVAL_FASTPATH = false
    }
}
