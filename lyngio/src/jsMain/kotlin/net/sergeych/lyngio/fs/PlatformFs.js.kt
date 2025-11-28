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

package net.sergeych.lyngio.fs

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okio.FileSystem
import okio.fakefilesystem.FakeFileSystem

@Suppress("UnsafeCastFromDynamic")
private fun systemFsOrNull(): FileSystem? = try {
    FileSystem.asDynamic().SYSTEM.unsafeCast<FileSystem>()
} catch (e: Throwable) {
    null
}

actual fun platformAsyncFs(): LyngFs = OkioAsyncFs(systemFsOrNull() ?: FakeFileSystem())
actual val LyngIoDispatcher: CoroutineDispatcher = Dispatchers.Default
