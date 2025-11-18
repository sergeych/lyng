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
 * Tests for route/anchor parsing utilities to support TOC navigation.
 */
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RouteParsingTest {
    @Test
    fun stripFragmentRemovesAnchor() {
        assertEquals("docs/Iterator.md", stripFragment("docs/Iterator.md#sec"))
        assertEquals("docs/Iterator.md", stripFragment("docs/Iterator.md"))
        assertEquals("docs/dir/file.md", stripFragment("docs/dir/file.md#x-y"))
    }

    @Test
    fun routeToPathDropsFragmentAndNormalizes() {
        assertEquals("docs/Iterator.md", routeToPath("docs/Iterator.md#part"))
        assertEquals("docs/Iterator.md", routeToPath("Iterator.md#part"))
        assertEquals("docs/guides/perf_guide.md", routeToPath("guides/perf_guide.md#toc"))
    }

    @Test
    fun anchorFromHashParsesSecondHash() {
        assertEquals("part", anchorFromHash("#/docs/Iterator.md#part"))
        assertEquals("sec-2", anchorFromHash("#/docs/g/it.md#sec-2"))
        assertNull(anchorFromHash("#/docs/Iterator.md"))
        assertNull(anchorFromHash("#just-a-frag"))
        assertNull(anchorFromHash("/not-a-hash"))
    }
}
