package net.sergeych.lyng

import net.sergeych.lyng.obj.Obj

actual object ArgBuilderProvider {
    actual fun acquire(): ArgsBuilder = NativeArgsBuilder()
}

private class NativeArgsBuilder : ArgsBuilder {
    private var buf: MutableList<Obj> = ArrayList()

    override fun reset(expectedSize: Int) {
        buf = ArrayList(expectedSize.coerceAtLeast(0))
    }

    override fun add(v: Obj) { buf.add(v) }
    override fun addAll(vs: List<Obj>) { if (vs.isNotEmpty()) buf.addAll(vs) }
    override fun build(tailBlockMode: Boolean): Arguments = Arguments(buf.toList(), tailBlockMode)
    override fun release() { /* no-op */ }
}
