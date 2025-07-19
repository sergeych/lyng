package net.sergeych.lynon

import net.sergeych.lyng.Scope
import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjBitBuffer
import net.sergeych.lyng.obj.ObjClass
import net.sergeych.lyng.obj.ObjString

// Most often used types:


val ObjLynonClass = object : ObjClass("Lynon") {

    suspend fun Scope.encodeAny(obj: Obj): Obj {
        val bout = MemoryBitOutput()
        val serializer = LynonEncoder(bout)
        serializer.encodeAny(this, obj)
        return ObjBitBuffer(bout.toBitArray())
    }

    suspend fun Scope.decodeAny(source: Obj): Obj {
        if( source !is ObjBitBuffer) throw Exception("Invalid source: $source")
        val bin = source.bitArray.toInput()
        val deserializer = LynonDecoder(bin)
        return deserializer.decodeAny(this)
    }

}.apply {
    addClassConst("test", ObjString("test_const"))
    addClassFn("encode") {
        encodeAny(requireOnlyArg<Obj>())
    }
    addClassFn("decode") {
        decodeAny(requireOnlyArg<Obj>())
    }
}