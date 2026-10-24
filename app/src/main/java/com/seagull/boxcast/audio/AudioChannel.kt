package com.seagull.boxcast.audio

import com.seagull.boxcast.frame.CarlifeFrame
import com.seagull.boxcast.net.BoxConnection
import com.seagull.boxcast.proto.Wire

/**
 * 音频通道（复刻官方 PCMMediaManager + MediaChannelSend + PCMPackageHead 实证）。
 *
 * 通道（2021 a/c.java 端口表实证）：
 *   channel 3 = 媒体音频（MediaSocket 9240）
 *   channel 4 = TTS（TTSSocket 9241）
 *   channel 5 = VR 语音（VRSocket 9242）
 *   控制命令（MIC_RECORD_* 等）走 channel 1
 *
 * PCM 裸流帧（PCMPackageHead 实证，12 字节大端头 + payload）：
 *   [dataLen:u32 BE][timeStamp:u32 BE][type:u32 BE][payload]
 *   type = MSG_MEDIA_INIT/STOP/PAUSE/RESUME_PLAY/SEEK_TO/DATA（EMusicPackageType 枚举实证）
 *
 * 初始化（sendInitParameter 实证）：
 *   CarlifeMusicInit{sampleRate, channelConfig, sampleFormat}
 *   默认 44100Hz / 2 声道 / 16bit（越界强制回退；AES 可选加密）
 *
 * AAC 模式：MSG_MEDIA_DATA_ENCODE(196615) 替代 MSG_MEDIA_DATA(196614)。
 */
object AudioChannel {

    /** 默认参数（sendInitParameter 实证） */
    const val DEFAULT_SAMPLE_RATE = 44100
    const val DEFAULT_CHANNEL_CONFIG = 2      // 2 = 双声道（原版强制）
    const val DEFAULT_SAMPLE_FORMAT = 16      // 16bit（原版强制）

    /** PCM 包头（12 字节大端） */
    class PcmHead {
        private val buf = ByteArray(12)

        fun dataLen(len: Int): PcmHead {
            CarlifeFrame.putU32BE(buf, 0, len.toLong()); return this
        }

        fun timeStamp(ts: Long): PcmHead {
            CarlifeFrame.putU32BE(buf, 4, ts); return this
        }

        fun timeStampNow(): PcmHead = timeStamp(System.currentTimeMillis())

        fun type(t: Int): PcmHead {
            CarlifeFrame.putU32BE(buf, 8, t.toLong()); return this
        }

        fun build(): ByteArray = buf.copyOf()
    }

    /** CarlifeMusicInit（proto，字段号实证：1=sampleRate 2=channelConfig 3=sampleFormat） */
    object MusicInit {
        fun encode(sampleRate: Int, channelConfig: Int, sampleFormat: Int): ByteArray =
            Wire.Writer().apply {
                int32(1, sampleRate)
                int32(2, channelConfig)
                int32(3, sampleFormat)
            }.toByteArray()
    }

    /**
     * 音频发送器：INIT → DATA… → STOP。
     * @param channel 目标 channel（默认 3 媒体音频）
     */
    class Sender(
        private val conn: BoxConnection,
        private val channel: Int = CarlifeFrame.Channel.MEDIA,
        private val sampleRate: Int = DEFAULT_SAMPLE_RATE,
        private val channelConfig: Int = DEFAULT_CHANNEL_CONFIG,
        private val sampleFormat: Int = DEFAULT_SAMPLE_FORMAT,
        private val aac: Boolean = false,
    ) {
        @Volatile var started = false
            private set

        /** 发 INIT（PCM 头 + CarlifeMusicInit proto，走音频 channel） */
        fun init() {
            val proto = MusicInit.encode(sampleRate, channelConfig, sampleFormat)
            val head = PcmHead().type(CarlifeFrame.CmdId.MSG_MEDIA_INIT)
                .dataLen(proto.size).timeStampNow().build()
            conn.sendRaw(CarlifeFrame.CmdId.MSG_MEDIA_INIT, head + proto, channel)
            started = true
        }

        /** 发一帧 PCM/AAC 裸数据（NORMAL 包：12B 头 + 数据） */
        fun write(data: ByteArray) {
            if (!started) return
            val type = if (aac) CarlifeFrame.CmdId.MSG_MEDIA_DATA_ENCODE else CarlifeFrame.CmdId.MSG_MEDIA_DATA
            val head = PcmHead().type(type).dataLen(data.size).timeStampNow().build()
            conn.sendRaw(type, head + data, channel)
        }

        fun pause() = control(CarlifeFrame.CmdId.MSG_MEDIA_PAUSE)
        fun resume() = control(CarlifeFrame.CmdId.MSG_MEDIA_RESUME_PLAY)
        fun seek() = control(CarlifeFrame.CmdId.MSG_MEDIA_SEEK_TO)
        fun stop() {
            control(CarlifeFrame.CmdId.MSG_MEDIA_STOP)
            started = false
        }

        private fun control(type: Int) {
            val head = PcmHead().type(type).dataLen(0).timeStampNow().build()
            conn.sendRaw(type, head, channel)
        }
    }
}
