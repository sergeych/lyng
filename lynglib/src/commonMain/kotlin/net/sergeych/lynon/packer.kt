package net.sergeych.lynon

import net.sergeych.lyng.Scope
import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjBuffer
import net.sergeych.lyng.obj.ObjClass
import net.sergeych.lyng.obj.ObjString

// Most often used types:


val ObjLynonClass = object : ObjClass("Lynon") {

    suspend fun Scope.encodeAny(obj: Obj): Obj {
        val bout = MemoryBitOutput()
        val serializer = LynonEncoder(bout)
        serializer.encodeAny(this, obj)
        return ObjBuffer(bout.toBitArray().bytes)
    }

    suspend fun Scope.decodeAny(buffer: ObjBuffer): Obj {
        val bin = BitArray(buffer.byteArray,8).toInput()
        val deserializer = LynonDecoder(bin)
        return deserializer.decodeAny(this)
    }

}.apply {
    addClassConst("test", ObjString("test_const"))
    addClassFn("encode") {
        encodeAny(requireOnlyArg<Obj>())
    }
    addClassFn("decode") {
        decodeAny(requireOnlyArg<ObjBuffer>())
    }
}