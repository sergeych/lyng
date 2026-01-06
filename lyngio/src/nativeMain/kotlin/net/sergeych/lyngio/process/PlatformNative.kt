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

import kotlin.experimental.ExperimentalNativeApi

@OptIn(ExperimentalNativeApi::class)
actual fun getPlatformDetails(): PlatformDetails {
    return PlatformDetails(
        name = Platform.osFamily.name,
        version = "unknown", 
        arch = Platform.cpuArchitecture.name,
        kernelVersion = getNativeKernelVersion()
    )
}

internal expect fun getNativeKernelVersion(): String?

actual fun isProcessSupported(): Boolean = isNativeProcessSupported()

internal expect fun isNativeProcessSupported(): Boolean

actual fun getSystemProcessRunner(): LyngProcessRunner = getNativeProcessRunner()

internal expect fun getNativeProcessRunner(): LyngProcessRunner
