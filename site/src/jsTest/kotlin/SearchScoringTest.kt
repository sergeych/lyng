import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchScoringTest {
    private fun rec(text: String, title: String = "Doc") = DocRecord("docs/a.md", title, norm(text))

    @Test
    fun zeroWhenNoTerms() {
        assertEquals(0, scoreQueryAdvanced(emptyList(), rec("hello world")))
    }

    @Test
    fun coverageMatters() {
        val r = rec("alpha beta gamma alpha beta")
        val s1 = scoreQueryAdvanced(listOf("alp"), r)
        val s2 = scoreQueryAdvanced(listOf("alp", "bet"), r)
        assertTrue(s2 > s1, "two-term coverage should score higher than one-term")
    }

    @Test
    fun proximityImprovesScore() {
        val near = rec("alpha beta gamma")
        val far = rec(("alpha "+"x ").repeat(50) + "beta")
        val sNear = scoreQueryAdvanced(listOf("alp", "bet"), near)
        val sFar = scoreQueryAdvanced(listOf("alp", "bet"), far)
        assertTrue(sNear > sFar, "closer terms should have higher score")
    }
}
