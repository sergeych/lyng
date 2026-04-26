# AI notes: publish JVM CLI updates with `bin/local_jrelease`

[//]: # (excludeFromIndex)

When a change affects the JVM CLI launcher used as `jlyng`, refresh the installed local distribution with:

```bash
bin/local_jrelease
```

Why:
- `jlyng` in this repo is installed from `~/bin/jlyng-jvm/lyng-jvm`, not directly from `lyng/build/install`.
- Manual copying from Gradle build output can leave the actual launcher on `PATH` stale.
- `bin/local_jrelease` rebuilds `lyng/build/distributions/lyng-jvm.zip`, reinstalls it under `~/bin/jlyng-jvm`, and recreates the `~/bin/jlyng` symlink.
