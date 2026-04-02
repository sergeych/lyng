# Networking Handoff

Date: 2026-04-02
Commit: `5346d15` (`Add KMP networking backends`)

## Scope completed

The `lyngio` networking work now provides a uniform Lyng-facing API with capability probes and platform-specific implementations.

Implemented modules:

- `lyng.io.http`
- `lyng.io.ws`
- `lyng.io.net`

## Current support matrix

### HTTP / HTTPS

- JVM: supported
- Android: supported
- JS: supported
- Linux Native: supported
- Windows Native (`mingwX64`): supported
- Apple Native: compile-verified on this Linux host

### WS / WSS

- JVM: supported
- Android: supported
- JS: supported
- Linux Native: supported
- Windows Native (`mingwX64`): supported
- Apple Native: compile-verified on this Linux host

### Raw networking (`lyng.io.net`)

- JVM: supported
- Android: supported
- JS/Node: supported
- JS/browser: unsupported by capability probe
- Linux Native: supported
- Apple Native: enabled via shared native backend; compile-verified, runtime not yet host-verified
- Other Native targets: intentionally still unsupported

## Important design decisions

- Ktor is the backend for all currently implemented networking.
- API is uniform across targets; platform variance is exposed through capability checks such as:
  - `Http.isSupported()`
  - `Ws.isSupported()`
  - `Net.isSupported()`
  - `Net.isTcpAvailable()`
  - `Net.isTcpServerAvailable()`
  - `Net.isUdpAvailable()`
- Native support was restricted to what matches Ktor client-engine support:
  - Darwin for Apple Native
  - Curl for Linux Native
  - WinHttp for Windows Native
- Native raw sockets use a shared Ktor socket implementation for Linux and Darwin source sets.
- Capability probes are enabled on Linux Native and Apple Native; Apple Native is compile-verified but not yet runtime-tested on a macOS host.

## Documentation and tests status

Docs updated:

- `docs/lyng.io.http.md`
- `docs/lyng.io.ws.md`
- `docs/lyng.io.net.md`

Verified docs/tests:

- HTTP/HTTPS docs are covered with extracted markdown tests on JVM.
- WS/WSS docs are covered with extracted markdown tests on JVM.
- JS/Node has both engine-level and Lyng-module-level tests for raw networking.

## Key implementation files

Shared:

- `lyngio/src/commonMain/kotlin/net/sergeych/lyngio/http/LyngHttp.kt`
- `lyngio/src/commonMain/kotlin/net/sergeych/lyng/io/http/LyngHttpModule.kt`
- `lyngio/src/commonMain/kotlin/net/sergeych/lyngio/ws/LyngWs.kt`
- `lyngio/src/commonMain/kotlin/net/sergeych/lyngio/net/LyngNet.kt`
- `lyngio/build.gradle.kts`
- `gradle/libs.versions.toml`

Platform HTTP:

- `lyngio/src/jvmMain/kotlin/net/sergeych/lyngio/http/PlatformJvm.kt`
- `lyngio/src/jsMain/kotlin/net/sergeych/lyngio/http/PlatformJs.kt`
- `lyngio/src/androidMain/kotlin/net/sergeych/lyngio/http/PlatformAndroid.kt`
- `lyngio/src/darwinMain/kotlin/net/sergeych/lyngio/http/PlatformDarwin.kt`
- `lyngio/src/linuxMain/kotlin/net/sergeych/lyngio/http/PlatformLinux.kt`
- `lyngio/src/mingwMain/kotlin/net/sergeych/lyngio/http/PlatformMingw.kt`

Platform WS:

- `lyngio/src/jvmMain/kotlin/net/sergeych/lyngio/ws/PlatformJvm.kt`
- `lyngio/src/jsMain/kotlin/net/sergeych/lyngio/ws/PlatformJs.kt`
- `lyngio/src/androidMain/kotlin/net/sergeych/lyngio/ws/PlatformAndroid.kt`
- `lyngio/src/darwinMain/kotlin/net/sergeych/lyngio/ws/PlatformDarwin.kt`
- `lyngio/src/linuxMain/kotlin/net/sergeych/lyngio/ws/PlatformLinux.kt`
- `lyngio/src/mingwMain/kotlin/net/sergeych/lyngio/ws/PlatformMingw.kt`

Platform raw net:

- `lyngio/src/jvmMain/kotlin/net/sergeych/lyngio/net/PlatformJvm.kt`
- `lyngio/src/jsMain/kotlin/net/sergeych/lyngio/net/PlatformJs.kt`
- `lyngio/src/androidMain/kotlin/net/sergeych/lyngio/net/PlatformAndroid.kt`
- `lyngio/src/nativeMain/kotlin/net/sergeych/lyngio/net/NativeKtorNetEngine.kt`
- `lyngio/src/linuxMain/kotlin/net/sergeych/lyngio/net/PlatformLinux.kt`
- `lyngio/src/darwinMain/kotlin/net/sergeych/lyngio/net/PlatformDarwin.kt`
- `lyngio/src/mingwMain/kotlin/net/sergeych/lyngio/net/PlatformMingw.kt`

JS tests:

- `lyngio/src/jsTest/kotlin/net/sergeych/lyngio/PlatformCapabilityJsTest.kt`
- `lyngio/src/jsTest/kotlin/net/sergeych/lyngio/NetJsNodeTest.kt`
- `lyngio/src/jsTest/kotlin/net/sergeych/lyng/io/net/LyngNetModuleJsNodeTest.kt`

JVM tests:

- `lyngio/src/jvmTest/kotlin/LyngioBookTest.kt`
- `lyngio/src/jvmTest/kotlin/net/sergeych/lyng/io/http/LyngHttpModuleTest.kt`
- `lyngio/src/jvmTest/kotlin/net/sergeych/lyng/io/ws/LyngWsModuleTest.kt`
- `lyngio/src/jvmTest/kotlin/net/sergeych/lyng/io/net/LyngNetModuleTest.kt`

Linux Native tests:

- `lyngio/src/linuxTest/kotlin/net/sergeych/lyngio/net/NetLinuxNativeTest.kt`

## Verification already run

JVM / docs:

- `./gradlew :lyngio:jvmTest --tests LyngioBookTest`
- `./gradlew :lyngio:jvmTest --tests net.sergeych.lyng.io.http.LyngHttpModuleTest`
- `./gradlew :lyngio:jvmTest --tests net.sergeych.lyng.io.ws.LyngWsModuleTest`
- `./gradlew :lyngio:jvmTest --tests net.sergeych.lyng.io.net.LyngNetModuleTest`

JS:

- `./gradlew :lyngio:compileKotlinJs`
- `./gradlew :lyngio:compileTestKotlinJs`
- `./gradlew :lyngio:jsNodeTest`
- `./gradlew kotlinUpgradeYarnLock`

Android:

- `./gradlew :lyngio:compileDebugKotlinAndroid`
- `./gradlew :lyngio:compileReleaseKotlinAndroid`

Native:

- `./gradlew :lyngio:compileKotlinLinuxX64`
- `./gradlew :lyngio:compileKotlinLinuxArm64`
- `./gradlew :lyngio:compileKotlinMingwX64`
- `./gradlew :lyngio:compileKotlinIosX64`
- `./gradlew :lyngio:compileKotlinIosArm64`
- `./gradlew :lyngio:compileKotlinIosSimulatorArm64`
- `./gradlew :lyngio:compileKotlinMacosArm64`
- `./gradlew :lyngio:compileTestKotlinLinuxX64`
- `./gradlew :lyngio:compileTestKotlinLinuxArm64`
- `./gradlew :lyngio:linkDebugTestLinuxX64`
- `./gradlew :lyngio:linuxX64Test`
- `./gradlew :lyngio:linuxX64Test --tests net.sergeych.lyngio.net.NetLinuxNativeTest`
- `./gradlew :lyngio:linuxX64Test --tests net.sergeych.lyngio.net.NetLinuxNativeTest.testLinuxNativeTcpAndUdpLoopback`
- `./lyngio/build/bin/linuxX64/debugTest/test.kexe --ktest_filter='net.sergeych.lyngio.net.NetLinuxNativeTest.*'`

## Known intentional gaps

- Native raw sockets are enabled on Linux Native and Apple Native.
- Apple Native raw networking is enabled based on shared-backend compile verification; runtime verification on macOS is still pending.
- No Android device/instrumented runtime tests were added; only compile verification was done.

## Worktree state after commit

Current HEAD:

- `5346d15` `Add KMP networking backends`

Unrelated remaining change:

- `examples/tetris_console.lyng`

That file was not touched by the networking work and was intentionally left out of the commit.

## Recommended next steps

1. Add Native raw socket support only per target that compiles and passes a smoke test.
2. Keep capability probes `false` on non-Linux Native targets until each raw-socket backend is proven.
3. If Apple Native work continues, compile Darwin targets on a macOS host before claiming support.
4. Run a macOS-hosted runtime smoke test when available to verify the already-enabled Darwin backend.
5. Optionally add Android runtime tests later; compile-only verification exists now.

## Suggested first task for the next chat

Continue Native raw `lyng.io.net` incrementally from the verified Linux baseline:

- keep Linux Native enabled
- keep Apple Native enabled unless runtime verification disproves the shared-backend assumption
- keep other Native targets capability-gated off until compiled and smoke-tested
- use only `ktor-network` support that actually compiles
- do not change the Lyng-facing API
