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

    /**
     * 数据通道 12B 头（权威 d/l.java:31-41 / d/n.java:31-41 / h/w.java:44-56 实证）：
     *   [0-3]=dataLen u32, [4-7]=timestamp u32,
     *   [8-9]=0 u16, [10]=channel u8(3=MEDIA/4=TTS/5=VR), [11]=codec u8(1=PCM/3=AAC)
     * 原按 [8-11]=serviceType u32 写 → 字段语义全错（2026/公签双报告 P0）
     */
    class PcmHead {
        private val buf = ByteArray(12)

        fun dataLen(len: Int): PcmHead {
            CarlifeFrame.putU32BE(buf, 0, len.toLong()); return this
        }

        fun timeStamp(ts: Long): PcmHead {
            CarlifeFrame.putU32BE(buf, 4, ts); return this
        }

        fun timeStampNow(): PcmHead = timeStamp(System.currentTimeMillis())

        /** @param ch 通道 3=MEDIA 4=TTS 5=VR；@param codec 1=PCM 3=AAC */
        fun channelCodec(ch: Int, codec: Int): PcmHead {
            buf[8] = 0; buf[9] = 0
            buf[10] = ch.toByte(); buf[11] = codec.toByte()
            return this
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
            // MusicInit 走控制通道（CMD，带内层头）；数据帧走 sendRawData 裸 12B 头
            val proto = MusicInit.encode(sampleRate, channelConfig, sampleFormat)
            conn.sendRaw(CarlifeFrame.CmdId.MSG_MEDIA_INIT, proto, CarlifeFrame.Channel.CMD)
            started = true
        }

        /** 发一帧 PCM/AAC 裸数据：[12B 头][data] 直写数据通道（去 inner+outer 双套） */
        fun write(data: ByteArray) {
            if (!started) return
            val codec = if (aac) 3 else 1
            val head = PcmHead().dataLen(data.size).timeStampNow()
                .channelCodec(channel, codec).build()
            conn.sendRawData(channel, head, data)
        }

        fun pause() = control(CarlifeFrame.CmdId.MSG_MEDIA_PAUSE)
        fun resume() = control(CarlifeFrame.CmdId.MSG_MEDIA_RESUME_PLAY)
        fun seek() = control(CarlifeFrame.CmdId.MSG_MEDIA_SEEK_TO)
        fun stop() {
            control(CarlifeFrame.CmdId.MSG_MEDIA_STOP)
            started = false
        }

        private fun control(type: Int) {
            // 控制类仍走 CMD 通道带内层头（与权威一致）
            conn.sendRaw(type, ByteArray(0), CarlifeFrame.Channel.CMD)
        }
    }
}
