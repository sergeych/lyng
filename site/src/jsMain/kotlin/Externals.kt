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
 * External/JS interop declarations used across the site.
 */

// MathJax v3 global API (loaded via CDN in index.html)
external object MathJax {
    fun typesetPromise(elements: Array<dynamic> = definedExternally): dynamic
    fun typeset(elements: Array<dynamic> = definedExternally)
}

// Ensure MathJax loader is bundled (self-host): importing the ES5 CHTML bundle has side effects
@JsModule("mathjax/es5/tex-chtml.js")
@JsNonModule
external val mathjaxBundle: dynamic

// JS JSON parser binding (avoid inline js("JSON.parse(...)"))
external object JSON {
    fun parse(text: String): dynamic
}

// URL encoding helpers
external fun encodeURI(uri: String): String
external fun encodeURIComponent(s: String): String
external fun decodeURIComponent(s: String): String
