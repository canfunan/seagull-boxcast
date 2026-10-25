package com.seagull.boxcast.proto

import java.io.ByteArrayOutputStream

/**
 * 极简 protobuf wire format 编解码（手写，不引第三方库）。
 *
 * wire type: 0=Varint 1=64bit 2=LengthDelimited 5=32bit
 * tag = (fieldNumber << 3) | wireType
 */
object Wire {

    const val WIRE_VARINT = 0
    const val WIRE_FIXED64 = 1
    const val WIRE_LEN = 2
    const val WIRE_FIXED32 = 5

    // ---------- 写 ----------

    class Writer {
        private val out = ByteArrayOutputStream(256)

        fun raw(b: ByteArray): Writer {
            out.write(b)
            return this
        }

        fun tag(field: Int, wire: Int): Writer = raw(encodeVarint(((field shl 3) or wire).toLong()))

        fun varint(v: Long): Writer {
            var x = v
            while (true) {
                if (x and 0x7FL.inv() == 0L) {
                    out.write(x.toInt())
                    return this
                }
                out.write(((x and 0x7F).toInt()) or 0x80)
                x = x ushr 7
            }
        }

        fun int32(field: Int, v: Int): Writer = tag(field, WIRE_VARINT).varint(v.toLong())
        fun int64(field: Int, v: Long): Writer = tag(field, WIRE_VARINT).varint(v)
        fun bool(field: Int, v: Boolean): Writer = tag(field, WIRE_VARINT).varint(if (v) 1 else 0)

        fun bytes(field: Int, b: ByteArray): Writer {
            tag(field, WIRE_LEN)
            varint(b.size.toLong())
            out.write(b)
            return this
        }

        fun string(field: Int, s: String): Writer = bytes(field, s.toByteArray(Charsets.UTF_8))

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    /** LEB128 varint 编码 */
    fun encodeVarint(v: Long): ByteArray {
        val w = Writer()
        w.varint(v)
        return w.toByteArray()
    }

    // ---------- 读 ----------

    class Reader(private val data: ByteArray, private var pos: Int = 0) {

        fun atEnd(): Boolean = pos >= data.size

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            var count = 0
            while (true) {
                if (pos >= data.size || count >= 10) {
                    throw IllegalArgumentException("bad varint: pos=$pos size=${data.size}")
                }
                val b = data[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
                count++
            }
            return result
        }

        fun readTag(): Pair<Int, Int> {
            val t = readVarint()
            return ((t shr 3).toInt()) to (t and 0x7).toInt()
        }

        fun readBytes(): ByteArray {
            val len = readVarint().toInt()
            if (len < 0 || pos + len > data.size) {
                throw IllegalArgumentException("bad len: $len (pos=$pos size=${data.size})")
            }
            val b = if (len == 0) ByteArray(0) else data.copyOfRange(pos, pos + len)
            pos += len
            return b
        }

        fun readString(): String = String(readBytes(), Charsets.UTF_8)
        fun readBool(): Boolean = readVarint() != 0L
        fun readInt32(): Int = readVarint().toInt()
        fun readInt64(): Long = readVarint()

        fun skip(wire: Int) {
            when (wire) {
                WIRE_VARINT -> readVarint()
                WIRE_FIXED64 -> { if (pos + 8 > data.size) throw IllegalArgumentException("skip64"); pos += 8 }
                WIRE_LEN -> { val n = readVarint().toInt(); if (n < 0 || pos + n > data.size) throw IllegalArgumentException("skipLen: $n"); pos += n }
                WIRE_FIXED32 -> { if (pos + 4 > data.size) throw IllegalArgumentException("skip32"); pos += 4 }
                // wire 3/4/6/7（group/deprecated）：**必须推进 pos**，否则 decode while(!atEnd()) 死循环
                else -> { if (pos >= data.size) throw IllegalArgumentException("skipUnknown"); pos += 1 }
            }
        }
    }
}
