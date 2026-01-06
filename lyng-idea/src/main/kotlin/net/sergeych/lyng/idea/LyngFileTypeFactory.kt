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
package net.sergeych.lyng.idea

import com.intellij.openapi.fileTypes.FileTypeConsumer
import com.intellij.openapi.fileTypes.FileTypeFactory
import com.intellij.openapi.fileTypes.WildcardFileNameMatcher

/**
 * Legacy way to register file type matchers, used here to robustly match *.lyng.d
 * without conflicting with standard .d extensions from other plugins.
 */
@Suppress("DEPRECATION")
class LyngFileTypeFactory : FileTypeFactory() {
    override fun createFileTypes(consumer: FileTypeConsumer) {
        // Register the multi-dot pattern explicitly
        consumer.consume(LyngFileType, WildcardFileNameMatcher("*.lyng.d"))
    }
}
