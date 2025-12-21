/*
 * Search results page: async, progressive site-wide search with relevance sorting.
 */

import androidx.compose.runtime.*
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import org.jetbrains.compose.web.attributes.*
import org.jetbrains.compose.web.dom.*

@Composable
fun SearchPage(route: String) {
    val terms = extractSearchTerms(route)
    var qRaw by remember { mutableStateOf(terms.joinToString(" ")) }
    var total by remember { mutableStateOf(0) }
    var checked by remember { mutableStateOf(0) }
    var done by remember { mutableStateOf(false) }
    data class Item(val rec: DocRecord, val score: Int)
    var items by remember { mutableStateOf(listOf<Item>()) }

    LaunchedEffect(route) {
        // Remember query in history
        rememberSearchQuery(qRaw)
        // Load index list
        try {
            checked = 0
            done = false
            items = emptyList()
            val resp = window.fetch("docs-index.json").await()
            if (!resp.ok) {
                total = 0
                done = true
                return@LaunchedEffect
            }
            val arr = (kotlin.js.JSON.parse<dynamic>(resp.text().await()) as Array<String>).toList()
            val list = arr.sorted()
            total = list.size
            // Process sequentially to keep UI responsive
            for (path in list) {
                try {
                    val url = "./" + encodeURI(path)
                    val r = window.fetch(url).await()
                    if (!r.ok) {
                        checked++
                        continue
                    }
                    val body = r.text().await()
                    if (body.contains("[//]: # (excludeFromIndex)")) {
                        checked++
                        continue
                    }
                    val title = extractTitleFromMarkdown(body) ?: path.substringAfterLast('/')
                    val plain = plainFromMarkdown(body)
                    val rec = DocRecord(path, title, plain)
                    val score = scoreQueryAdvanced(terms, rec)
                    if (score > 0) {
                        val newList = (items + Item(rec, score)).sortedByDescending { it.score }.take(200)
                        items = newList
                    }
                } catch (_: Throwable) { /* ignore one entry errors */ }
                checked++
                // Yield to UI
                delay(0)
            }
        } finally {
            done = true
        }
    }

    H1 { Text("Search") }
    if (terms.isEmpty()) {
        P { Text("Type something in the search box above.") }
        return
    }
    P {
        Text("Query: ")
        Code { Text(qRaw) }
    }
    if (!done) {
        Div({ classes("d-flex", "align-items-center", "gap-2", "mb-3") }) {
            Div({ classes("spinner-border", "spinner-border-sm") }) {}
            Text("Searching… $checked / $total")
        }
    }
    if (items.isEmpty() && done) {
        P { Text("No results found.") }
    } else {
        Ul({ classes("list-unstyled") }) {
            items.forEach { item ->
                Li({ classes("mb-3") }) {
                    val href = "#/${item.rec.path}?q=" + encodeURIComponent(qRaw)
                    A(attrs = { attr("href", href) }) { Text(item.rec.title) }
                    Br()
                    Small({ classes("text-muted") }) { Text(item.rec.path.substringAfter("docs/")) }
                }
            }
        }
    }
}

// Advanced relevance scoring: combines coverage of distinct terms and proximity (minimal window).
internal fun scoreQueryAdvanced(termsIn: List<String>, rec: DocRecord): Int {
    val terms = termsIn.filter { it.isNotBlank() }.map { it.lowercase() }.distinct()
    if (terms.isEmpty()) return 0
    val title = norm(rec.title)
    val text = rec.text

    // Title coverage bonus
    var titleCoverage = 0
    for (t in terms) if (title.contains(t)) titleCoverage++

    // Tokenize body and collect positions for each term by first prefix match
    val wordRegex = Regex("[A-Za-z0-9_]+")
    val posMap = HashMap<String, MutableList<Int>>()
    var pos = 0
    for (m in wordRegex.findAll(text)) {
        val token = m.value.lowercase()
        var matched: String? = null
        var best = 0
        for (t in terms) {
            if (token.startsWith(t) && t.length > best) { best = t.length; matched = t }
        }
        if (matched != null) {
            posMap.getOrPut(matched) { mutableListOf() }.add(pos)
        }
        pos++
        if (pos > 100000) break // safety for extremely large docs
    }
    val coverage = terms.count { (posMap[it]?.isNotEmpty()) == true }
    if (coverage == 0) return 0

    // Proximity: minimal window length covering at least one occurrence of each covered term
    var minWindow = Int.MAX_VALUE
    if (coverage >= 2) {
        // Prepare lists
        val lists = terms.mapNotNull { t -> posMap[t]?.let { t to it } }.toList()
        // K-way merge style window across sorted lists
        val idx = IntArray(lists.size) { 0 }
        // Initialize current positions
        fun currentWindow(): Int {
            var minPos = Int.MAX_VALUE
            var maxPos = Int.MIN_VALUE
            for (i in lists.indices) {
                val p = lists[i].second[idx[i]]
                if (p < minPos) minPos = p
                if (p > maxPos) maxPos = p
            }
            return maxPos - minPos + 1
        }
        // Iterate advancing the pointer at the list with minimal current position
        while (true) {
            val win = currentWindow()
            if (win < minWindow) minWindow = win
            // Find list with minimal current pos
            var minI = 0
            var minVal = lists[0].second[idx[0]]
            for (i in 1 until lists.size) {
                val v = lists[i].second[idx[i]]
                if (v < minVal) { minVal = v; minI = i }
            }
            idx[minI]++
            if (idx[minI] >= lists[minI].second.size) break
            // stop if window is already 1 (best possible)
            if (minWindow <= 1) break
        }
        if (minWindow == Int.MAX_VALUE) minWindow = 100000
    }

    val coverageScore = coverage * 10000
    val proximityScore = if (coverage >= 2) (5000.0 / (1 + minWindow)).toInt() else 0
    val titleScore = titleCoverage * 3000
    // Slight preference for shorter text
    val lengthAdj = (200 - kotlin.math.min(200, text.length / 500))
    return coverageScore + proximityScore + titleScore + lengthAdj
}
