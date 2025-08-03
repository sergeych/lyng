package net.sergeych.lynon

import net.sergeych.bintools.ByteChunk
import net.sergeych.lyng.Scope
import net.sergeych.lyng.obj.*

enum class LynonType(val objClass: ObjClass, val defaultFrequency: Int = 1) {
    Null(ObjNull.objClass, 80),
    Int0(ObjInt.type, 70),
    IntNegative(ObjInt.type, 50),
    IntPositive(ObjInt.type, 100),
    IntSigned(ObjInt.type, 30),
    String(ObjString.type, 100),
    Real(ObjReal.type),
    Bool(ObjBool.type, 80),
    List(ObjList.type, 70),
    Map(ObjMap.type, 40),
    Set(ObjSet.type),
    Buffer(ObjBuffer.type, 50),
    Instant(ObjInstant.type, 30),
    Duration(ObjDuration.type),
    Other(Obj.rootObjectType, 60);

    fun generalizeTo(other: LynonType): LynonType? {
        if (this == other) return this
        return (if (this.isInt && other.isInt) {
            when {
                this == Int0 -> other // upgrade 0 to some other int
                other == Int0 -> this // 0 is member of our class, ignore
                // different signum propagate to signed
                else -> IntSigned
            }
        } else
        // impossible to generalize
            null
                ).also { println("Gen $this + $other -> $it") }
    }

    val isInt by lazy {
        when (this) {
            Int0, IntSigned, IntPositive, IntNegative -> true
            else -> false
        }
    }

}

open class LynonEncoder(val bout: BitOutput, val settings: LynonSettings = LynonSettings.default) {

    val cache = mutableMapOf<Any, Int>()

    suspend fun encodeCached(item: Any, packer: suspend LynonEncoder.() -> Unit) {

        suspend fun serializeAndCache(key: Any = item) {
            cache[key]?.let { cacheId ->
//                println("encode: Cache hit: ${cacheId}: $item: ${item::class.simpleName}")
                val size = sizeInBits(cache.size)
                bout.putBit(1)
                bout.putBits(cacheId.toULong(), size)
            } ?: run {
                bout.putBit(0)
                if (settings.shouldCache(item)) {
//                    println("encode add cache: ${cache.size}: $item: ${item::class.simpleName}")
                    packer()
                    cache[key] = cache.size
                } else {
//                    println("encode but not cache $item")
                    packer()
                }
            }
        }

        when (item) {
            is ByteArray -> serializeAndCache(ByteChunk(item.asUByteArray()))
            is UByteArray -> serializeAndCache(ByteChunk(item))
            else -> serializeAndCache(item)
        }
    }

    /**
     * Encode any Lyng object [Obj], which can be serialized, using type record. This allow to
     * encode any object with the overhead of type record.
     *
     * Caching is used automatically.
     */
    suspend fun encodeAny(scope: Scope, obj: Obj) {
        encodeCached(obj) {
            val type = putTypeRecord(obj, obj.lynonType())
            obj.serialize(scope, this, type)
        }
    }

    private fun putTypeRecord(obj: Obj, type: LynonType): LynonType {
        putType(type)
        return type
    }

    private fun putType(type: LynonType) {
        bout.putBits(type.ordinal.toULong(), 4)
    }

    /**
     * AnyList could be homogenous (first bit=1) and heterogeneous (first bit=0). Homogenous list
     * has a single type record that precedes the list, heterogeneous hash typed record
     * for each item.
     *
     */
    suspend fun encodeAnyList(scope: Scope, list: List<Obj>) {
        val objClass = list[0].objClass
        var type = list[0].lynonType()
        var isHomogeneous = true
        for (i in list.drop(1))
            if (i.objClass != objClass) {
                isHomogeneous = false
                break
            } else {
                // same class but type might need generalization
                type = type.generalizeTo(i.lynonType())
                    ?: scope.raiseError("inner error: can't generalize lynon type $type to ${i.lynonType()}")
            }
        if (isHomogeneous) {
            putBit(1)
            putTypeRecord(list[0], type)
            encodeUnsigned(list.size.toULong())
            for (i in list) encodeObject(scope, i, type)
        } else {
            putBit(0)
            encodeUnsigned(list.size.toULong())
            for (i in list) encodeAny(scope, i)
        }

    }

    /**
     * Write object _with no type record_: type is known
     */
    suspend fun encodeObject(scope: Scope, obj: Obj,overrideType: LynonType? = null) {
        encodeCached(obj) {
            obj.serialize(scope, this, overrideType)
        }
    }

    suspend fun encodeCachedBytes(bytes: ByteArray) {
        encodeCached(bytes) {
            bout.compress(bytes)
        }
    }

    fun encodeBinaryData(data: ByteArray) {
        bout.compress(data)
    }

    fun encodeSigned(value: Long) {
        bout.packSigned(value)
    }

    @Suppress("unused")
    fun encodeUnsigned(value: ULong) {
        bout.packUnsigned(value)
    }

    @Suppress("unused")
    fun encodeBool(value: Boolean) {
        bout.putBit(if (value) 1 else 0)
    }

    fun encodeReal(value: Double) {
        bout.putBits(value.toRawBits().toULong(), 64)
    }

    fun encodeBoolean(value: Boolean) {
        bout.putBit(if (value) 1 else 0)
    }

    fun putBits(value: Int, sizeInBits: Int) {
        bout.putBits(value.toULong(), sizeInBits)
    }

    fun putBit(bit: Int) {
        bout.putBit(bit)
    }

}