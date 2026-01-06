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

package net.sergeych.lyngio.process

/**
 * Platform core details.
 */
data class PlatformDetails(
    val name: String,
    val version: String,
    val arch: String,
    val kernelVersion: String? = null
)

/**
 * Get the current platform core details.
 */
expect fun getPlatformDetails(): PlatformDetails

/**
 * Check whether the current platform supports processes and shell execution.
 */
expect fun isProcessSupported(): Boolean

/**
 * Get the system default [LyngProcessRunner].
 * Throws [UnsupportedOperationException] if processes are not supported on this platform.
 */
expect fun getSystemProcessRunner(): LyngProcessRunner
