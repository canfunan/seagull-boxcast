package com.seagull.boxcast.inject

import com.seagull.boxcast.proto.Wire

/**
 * 反控触摸消息（标准 CarLife 协议 425985 段，字段号实证）。
 *
 * CarlifeTouchAction（425985）：1=action 2=x 3=y 4=pointerx 5=pointery
 *   action 语义与 MotionEvent 一致（0=DOWN 1=UP 2=MOVE）——2021 f/y.java 直接
 *   MotionEvent.obtain(t, t, parseFrom.getAction(), x, y, 0) 实证。
 * CarlifeCarHardKeyCode（425992）：1=keycode
 */
object TouchProto {

    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
    const val ACTION_MOVE = 2

    data class TouchAction(
        val action: Int = 0,
        val x: Int = 0,
        val y: Int = 0,
        val pointerx: Int = 0,
        val pointery: Int = 0,
    ) {
        fun encode(): ByteArray = Wire.Writer().apply {
            int32(1, action)
            int32(2, x)
            int32(3, y)
            int32(4, pointerx)
            int32(5, pointery)
        }.toByteArray()

        companion object {
            fun decode(d: ByteArray): TouchAction = Wire.Reader(d).run {
                var m = TouchAction()
                while (!atEnd()) {
                    val (f, w) = readTag()
                    when (f) {
                        1 -> m = m.copy(action = readInt32())
                        2 -> m = m.copy(x = readInt32())
                        3 -> m = m.copy(y = readInt32())
                        4 -> m = m.copy(pointerx = readInt32())
                        5 -> m = m.copy(pointery = readInt32())
                        else -> skip(w)
                    }
                }
                m
            }
        }
    }

    data class HardKeyCode(val keycode: Int = 0) {   // 1
        fun encode(): ByteArray = Wire.Writer().int32(1, keycode).toByteArray()

        companion object {
            fun decode(d: ByteArray): HardKeyCode = Wire.Reader(d).run {
                var k = 0
                while (!atEnd()) {
                    val (f, w) = readTag()
                    if (f == 1) k = readInt32() else skip(w)
                }
                HardKeyCode(k)
            }
        }
    }
}
