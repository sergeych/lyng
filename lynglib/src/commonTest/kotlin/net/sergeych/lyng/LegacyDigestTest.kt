/*
 * Copyright 2026 Sergey S. Chernov real.sergeych@gmail.com
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

package net.sergeych.lyng

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LegacyDigestTest {

    // --- Kotlin-level unit tests for the SHA-1 implementation ---

    @Test
    fun sha1KotlinEmptyString() {
        // SHA-1("") = da39a3ee5e6b4b0d3255bfef95601890afd80709
        assertEquals(
            "da39a3ee5e6b4b0d3255bfef95601890afd80709",
            LegacyDigest.sha1Hex(ByteArray(0))
        )
    }

    @Test
    fun sha1KotlinAbc() {
        // SHA-1("abc") = a9993e364706816aba3e25717850c26c9cd0d89d
        assertEquals(
            "a9993e364706816aba3e25717850c26c9cd0d89d",
            LegacyDigest.sha1Hex("abc".encodeToByteArray())
        )
    }

    @Test
    fun sha1KotlinLongerMessage() {
        // SHA-1("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq")
        // = 84983e441c3bd26ebaae4aa1f95129e5e54670f1
        val msg = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"
        assertEquals(
            "84983e441c3bd26ebaae4aa1f95129e5e54670f1",
            LegacyDigest.sha1Hex(msg.encodeToByteArray())
        )
    }

    @Test
    fun sha1KotlinExactlyOneBlock() {
        // "The quick brown fox jumps over the lazy dog"
        // SHA-1 = 2fd4e1c67a2d28fced849ee1bb76e7391b93eb12
        val msg = "The quick brown fox jumps over the lazy dog"
        assertEquals(
            "2fd4e1c67a2d28fced849ee1bb76e7391b93eb12",
            LegacyDigest.sha1Hex(msg.encodeToByteArray())
        )
    }

    // --- Lyng-level integration tests ---

    @Test
    fun sha1LyngStringInput() = runTest {
        eval(
            """
            import lyng.legacy_digest
            assertEquals(
                "a9993e364706816aba3e25717850c26c9cd0d89d",
                LegacyDigest.sha1("abc")
            )
            assertEquals(
                "da39a3ee5e6b4b0d3255bfef95601890afd80709",
                LegacyDigest.sha1("")
            )
            """.trimIndent()
        )
    }

    @Test
    fun sha1LyngBufferInput() = runTest {
        eval(
            """
            import lyng.legacy_digest
            import lyng.buffer
            val buf = Buffer.decodeHex("616263")  // "abc" in hex
            assertEquals(
                "a9993e364706816aba3e25717850c26c9cd0d89d",
                LegacyDigest.sha1(buf)
            )
            """.trimIndent()
        )
    }

    @Test
    fun sha1LyngReturnType() = runTest {
        eval(
            """
            import lyng.legacy_digest
            val h = LegacyDigest.sha1("hello")
            assert(h is String)
            assertEquals(40, h.length)
            """.trimIndent()
        )
    }
}
