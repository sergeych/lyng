/*
 * Copyright 2026 Sergey S. Chernov
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
 */

package net.sergeych.lyng

/**
 * Compile-time call metadata for known functions. Used to select lambda receiver semantics.
 */
data class CallSignature(
    val tailBlockReceiverType: String? = null,
    val inlineHigherOrder: HigherOrderInline? = null
) {
    data class HigherOrderInline(
        val kind: Kind,
        val result: ResultMode,
        val argCount: Int = 1,
        val lambdaArgIndex: Int = 0
    )

    enum class Kind {
        UNARY_ARGUMENT,
        RECEIVER,
        ITERABLE,
        MAP_GET_OR_PUT
    }

    enum class ResultMode {
        BLOCK_RESULT,
        RETURN_RECEIVER,
        FOR_EACH,
        MAP,
        FILTER,
        MAP_NOT_NULL,
        ASSOCIATE_BY
    }
}
