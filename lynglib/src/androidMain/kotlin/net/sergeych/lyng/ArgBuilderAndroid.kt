package net.sergeych.lyng

import net.sergeych.lyng.obj.Obj

actual object ArgBuilderProvider {
    private val tl = object : ThreadLocal<AndroidArgsBuilder>() {
        override fun initialValue(): AndroidArgsBuilder = AndroidArgsBuilder()
    }
    actual fun acquire(): ArgsBuilder = tl.get()
}

private class AndroidArgsBuilder : ArgsBuilder {
    private val buf: ArrayList<Obj> = ArrayList(8)

    override fun reset(expectedSize: Int) {
        buf.clear()
        if (expectedSize > 0) buf.ensureCapacity(expectedSize)
    }

    override fun add(v: Obj) { buf.add(v) }

    override fun addAll(vs: List<Obj>) {
        if (vs.isNotEmpty()) {
            buf.ensureCapacity(buf.size + vs.size)
            buf.addAll(vs)
        }
    }

    override fun build(tailBlockMode: Boolean): Arguments = Arguments(buf.toList(), tailBlockMode)

    override fun release() { /* no-op, ThreadLocal keeps buffer */ }
}
