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

package net.sergeych.lyng.io.testtls

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.TrustManagerFactory
import kotlin.io.path.absolutePathString

internal object TlsTestMaterial {
    private const val STORE_PASSWORD = "changeit"
    private const val KEY_ALIAS = "lyng-io-test"

    private val dir: Path by lazy { Files.createTempDirectory("lyng-io-tls-test-") }
    private val serverStore: Path by lazy { dir.resolve("server.p12") }
    private val trustStore: Path by lazy { dir.resolve("trust.p12") }
    private val certFile: Path by lazy { dir.resolve("server.cer") }

    val serverSocketFactory: SSLServerSocketFactory by lazy {
        ensureGenerated()
        val keyStore = KeyStore.getInstance("PKCS12")
        serverStore.toFile().inputStream().use { keyStore.load(it, STORE_PASSWORD.toCharArray()) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, STORE_PASSWORD.toCharArray())
        SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, null, null)
        }.serverSocketFactory
    }

    val sslContext: SSLContext by lazy {
        ensureGenerated()
        val keyStore = KeyStore.getInstance("PKCS12")
        serverStore.toFile().inputStream().use { keyStore.load(it, STORE_PASSWORD.toCharArray()) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, STORE_PASSWORD.toCharArray())
        val trust = KeyStore.getInstance("PKCS12")
        trustStore.toFile().inputStream().use { trust.load(it, STORE_PASSWORD.toCharArray()) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(trust)
        SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, tmf.trustManagers, null)
        }
    }

    fun installJvmClientTrust() {
        ensureGenerated()
        System.setProperty("javax.net.ssl.trustStore", trustStore.absolutePathString())
        System.setProperty("javax.net.ssl.trustStorePassword", STORE_PASSWORD)
        System.setProperty("javax.net.ssl.trustStoreType", "PKCS12")

        val trust = KeyStore.getInstance("PKCS12")
        trustStore.toFile().inputStream().use { trust.load(it, STORE_PASSWORD.toCharArray()) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(trust)
        SSLContext.setDefault(
            SSLContext.getInstance("TLS").apply {
                init(null, tmf.trustManagers, null)
            }
        )
    }

    private fun ensureGenerated() {
        if (Files.exists(serverStore) && Files.exists(trustStore) && Files.exists(certFile)) return
        runKeytool(
            "-genkeypair",
            "-alias", KEY_ALIAS,
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", "2",
            "-storetype", "PKCS12",
            "-keystore", serverStore.absolutePathString(),
            "-storepass", STORE_PASSWORD,
            "-keypass", STORE_PASSWORD,
            "-dname", "CN=127.0.0.1, OU=Lyng, O=Lyng, L=Test, ST=Test, C=US",
            "-ext", "SAN=ip:127.0.0.1,dns:localhost",
        )
        runKeytool(
            "-exportcert",
            "-alias", KEY_ALIAS,
            "-keystore", serverStore.absolutePathString(),
            "-storepass", STORE_PASSWORD,
            "-rfc",
            "-file", certFile.absolutePathString(),
        )
        runKeytool(
            "-importcert",
            "-noprompt",
            "-alias", KEY_ALIAS,
            "-storetype", "PKCS12",
            "-keystore", trustStore.absolutePathString(),
            "-storepass", STORE_PASSWORD,
            "-file", certFile.absolutePathString(),
        )
    }

    private fun runKeytool(vararg args: String) {
        val process = ProcessBuilder(listOf("keytool") + args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        require(code == 0) { "keytool failed: $output" }
    }
}
