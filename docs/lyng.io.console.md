### lyng.io.console

`lyng.io.console` provides optional rich console support for terminal applications.

> **Note:** this module is part of `lyngio`. It must be explicitly installed into the import manager by host code.
>
> **CLI note:** the `lyng` CLI now installs `lyng.io.console` in its base scope by default, so scripts can simply `import lyng.io.console`.

#### Install in host

```kotlin
import net.sergeych.lyng.Script
import net.sergeych.lyng.io.console.createConsoleModule
import net.sergeych.lyngio.console.security.PermitAllConsoleAccessPolicy

suspend fun initScope() {
    val scope = Script.newScope()
    createConsoleModule(PermitAllConsoleAccessPolicy, scope)
}
```

#### Use in Lyng script

```lyng
import lyng.io.console

println("supported = " + Console.isSupported())
println("tty = " + Console.isTty())
println("ansi = " + Console.ansiLevel())
println("geometry = " + Console.geometry())

Console.write("hello\n")
Console.home()
Console.clear()
Console.moveTo(1, 1)
Console.clearLine()
Console.enterAltScreen()
Console.leaveAltScreen()
Console.setCursorVisible(true)
Console.flush()
```

#### Tetris sample

The repository includes a full interactive Tetris sample that demonstrates:

- alternate screen rendering
- raw keyboard input
- resize handling
- typed console events

![Lyng Tetris sample](/tetris.png)

Run it from the project root in a real TTY:

```bash
lyng examples/tetris_console.lyng
```

#### API

- `Console.isSupported(): Bool` — whether console control is available on this platform/runtime.
- `Console.isTty(): Bool` — whether output is attached to a TTY.
- `Console.ansiLevel(): ConsoleAnsiLevel` — `NONE`, `BASIC16`, `ANSI256`, `TRUECOLOR`.
- `Console.geometry(): ConsoleGeometry?` — `{columns, rows}` as typed object or `null`.
- `Console.details(): ConsoleDetails` — consolidated capability object.
- `Console.write(text: String)` — writes to console output.
- `Console.flush()` — flushes buffered output.
- `Console.home()` — moves cursor to top-left.
- `Console.clear()` — clears visible screen.
- `Console.moveTo(row: Int, column: Int)` — moves cursor to 1-based row/column.
- `Console.clearLine()` — clears current line.
- `Console.enterAltScreen()` — switch to alternate screen buffer.
- `Console.leaveAltScreen()` — return to normal screen buffer.
- `Console.setCursorVisible(visible: Bool)` — shows/hides cursor.
- `Console.events(): ConsoleEventStream` — endless iterable source of typed events: `ConsoleResizeEvent`, `ConsoleKeyEvent`.
- `Console.setRawMode(enabled: Bool): Bool` — requests raw input mode, returns `true` if changed.

#### Event Iteration

Use events from a loop, typically in a separate coroutine:

```lyng
launch {
    for (ev in Console.events()) {
        if (ev is ConsoleKeyEvent) {
            // handle key
        }
    }
}
```

#### Event format

`Console.events()` emits `ConsoleEvent` with:

- `type: ConsoleEventType` — `UNKNOWN`, `RESIZE`, `KEY_DOWN`, `KEY_UP`

Additional fields:

- `ConsoleResizeEvent`: `columns`, `rows`
- `ConsoleKeyEvent`: `key`, `code`, `ctrl`, `alt`, `shift`, `meta`

#### Security policy

The module uses `ConsoleAccessPolicy` with operations:

- `WriteText(length)`
- `ReadEvents`
- `SetRawMode(enabled)`

For permissive mode, use `PermitAllConsoleAccessPolicy`.
