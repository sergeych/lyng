package net.sergeych.lyng

import net.sergeych.lyng.obj.Obj

/**
 * Helper factory for argument-building mutable lists.
 * Currently returns a fresh ArrayList with the requested initial capacity.
 * JVM-specific pooling/builder can be introduced later via expect/actual without
 * changing call sites that use [newArgMutableList].
 */
fun newArgMutableList(initialCapacity: Int): MutableList<Obj> = ArrayList(initialCapacity)
