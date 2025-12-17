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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.sergeych.lyng.Scope
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.test.Test
import kotlin.time.Clock

suspend fun executeSampleTests(fileName: String) {
    val sample = withContext(Dispatchers.IO) {
        Files.readString(Paths.get(fileName))
    }
    runBlocking {
        val c = Scope()
            val start = Clock.System.now()
            c.eval(sample)
            val time = Clock.System.now() - start
            println("$time: $fileName")
//            delay(100)
//        }
    }
}

class SamplesTest {

    @Test
    fun testSamples() = runBlocking {
        for (s in Files.list(Paths.get("../docs/samples"))) {
            if( s.fileName.toString() == "fs_sample.lyng" ) continue
            if (s.extension == "lyng") executeSampleTests(s.toString())
        }
    }
}