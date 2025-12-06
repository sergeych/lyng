import kotlinx.browser.window
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchHistoryTest {
    @BeforeTest
    fun resetStorage() {
        window.localStorage.removeItem("lyng.search.history")
    }

    @Test
    fun keepsOnlySevenMostRecentUnique() {
        // Add 9 queries
        for (i in 1..9) rememberSearchQuery("query $i")
        val raw = window.localStorage.getItem("lyng.search.history") ?: ""
        val list = raw.split('\n').filter { it.isNotBlank() }
        assertEquals(7, list.size, "history should contain 7 items")
        assertEquals("query 9", list[0], "most recent should be first")
        assertEquals("query 3", list.last(), "oldest retained should be 3")

        // Re-insert existing should move to front without duplicates
        rememberSearchQuery("query 5")
        val raw2 = window.localStorage.getItem("lyng.search.history") ?: ""
        val list2 = raw2.split('\n').filter { it.isNotBlank() }
        assertEquals(7, list2.size)
        assertEquals("query 5", list2[0])
        assertTrue(list2.count { it == "query 5" } == 1)
    }
}
