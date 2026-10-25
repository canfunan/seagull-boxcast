package com.seagull.boxcast.frame

/**
 * CarLife 两层帧结构（逆向实证）。
 *
 * 外层（USB AOA / socket 流）：
 *   [channelId: u32 大端][dataLen: u32 大端][payload: dataLen]
 *   —— com.baidu.carlife.d.d（2021）：channelId∈[1,6]，dataLen≤67108864(64MB)
 *
 * 内层（CarlifeCmdMessage，com.baidu.carlife.r.java f() 组装逻辑实证）：
 *   [dataLen: u16 大端][reserved: u16 大端][serviceType: u32 大端][payload: dataLen]
 *
 * 盒子协议消息全部走 channel 1（cmd 通道），serviceType = 1024xx 段。
 */
object CarlifeFrame {

    /** 逻辑 channel（2021 版 a/c.java 端口表实证） */
    object Channel {
        const val CMD = 1        // 7240 / 控制
        const val VIDEO = 2      // 8240
        const val MEDIA = 3      // 9240
        const val TTS = 4        // 9241
        const val VR = 5         // 9242
        const val TOUCH = 6      // 9340
    }

    /** 盒子协议消息 ID（CommonParams.java 实证） */
    object CmdId {
        const val BOX_CHANGE_FRAME_WH = 102401
        const val BOX_VIDEO_HEARTBEAT = 102402
        const val BOX_HELLO = 102403
        const val BOX_H264_LIST = 102404
        const val BOX_H264LIST_REQUEST = 102405
        const val BOX_H264LIST_RESPONSE = 102406
        const val BOX_SEND_H264_REQUEST = 102407
        const val BOX_SEND_H264_START = 102408
        const val BOX_SEND_H264_DATA = 102409
        const val BOX_SEND_H264_END = 102410
        const val BOX_H264_DONE = 102411
        const val BOX_CHECK_UPDATE_REQ = 102416
        const val BOX_CHECK_UPDATE_RES = 102417
        const val BOX_VEHCILE_STATE = 102418

        // 反控（标准 CarLife 协议段，2021 f/y.java 实证）
        const val TOUCH_ACTION = 425985                 // CarlifeTouchAction
        const val TOUCH_CAR_HARD_KEY_CODE = 425992      // CarlifeCarHardKeyCode

        // 标准视频流（Recorder.sendVideoData 实证：channel 2 + [12B 头][H.264] 两段）
        const val MSG_VIDEO_DATA = 131073
        const val MSG_VIDEO_HEARTBEAT = 131074

        // 视频编码器握手（VideoMsgHandler 实证：INIT→INIT_DONE→START→推流）
        const val MSG_CMD_VIDEO_ENCODER_INIT = 98311
        const val MSG_CMD_VIDEO_ENCODER_START = 98313
        const val MSG_CMD_VIDEO_ENCODER_PAUSE = 98314
        const val MSG_CMD_VIDEO_ENCODER_RESET = 98315
        const val MSG_CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE = 98316
        const val MSG_CMD_VIDEO_ENCODER_INIT_DONE = 65544
        const val MSG_CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE = 65549
        const val MSG_CMD_VIDEO_ADAPT_DONE = 1040

        // 握手/版本/统计（CarlifeConnectHsgHandler addMsg 全集实证）
        const val MSG_CMD_HU_INFO = 98307
        const val MSG_CMD_HU_PROTOCOL_VERSION = 98305
        const val MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS = 65538
        const val MSG_CMD_MD_INFO = 65540
        const val MSG_CMD_STATISTIC_INFO = 98343
        const val MSG_CMD_VEHICLE_FOREGROUND = 98455
        const val MSG_CMD_VEHICLE_BACKGROUND = 98456
        const val MSG_CMD_SCREEN_ON = 65560
        const val MSG_CMD_SCREEN_OFF = 65561
        const val MSG_CMD_SCREEN_USERPRESENT = 65562

        // 加密握手（EncryptSetupManager 实证：RSA 换 AES seed，之后全消息 AES）
        const val MSG_CMD_MD_RSA_PUBLIC_KEY_REQUEST = 65642
        const val MSG_CMD_MD_AES_KEY_SEND_REQUEST = 65644
        const val MSG_CMD_HU_RSA_PUBLIC_KEY_RESPONSE = 98411
        const val MSG_CMD_HU_AES_REC_RESPONSE = 98413

        // 车机 HUD 增强（std 权威 ID）
        const val MSG_CMD_MEDIA_INFO = 65589      // 当前歌曲（MediaSession 读取）
        const val MSG_CMD_MODULE_STATUS = 65574   // 模块状态
        const val MSG_CMD_TIME_SYNC = 65632       // 时间同步（CommonParams.java:298 权威）

        // HU 索权（CarlifeConnectHsgHandler 实证）
        const val MSG_PERMISSION_READ_WRITE = 65579
        const val MSG_PERMISSION_LOCATION = 65581
        const val MSG_PERMISSION_RECORD = 65580
        const val MSG_PERMISSION_LOCATION_HOME = 65594

        // 音频段（CommonParams 实证；channel 3=媒体 4=TTS 5=VR）
        // 媒体音频 196xxx
        const val MSG_MEDIA_INIT = 196609
        const val MSG_MEDIA_STOP = 196610
        const val MSG_MEDIA_PAUSE = 196611
        const val MSG_MEDIA_RESUME_PLAY = 196612
        const val MSG_MEDIA_SEEK_TO = 196613
        const val MSG_MEDIA_DATA = 196614               // PCM 裸流
        const val MSG_MEDIA_DATA_ENCODE = 196615        // AAC 编码流（AAC_SUPPORT）
        // TTS 262xxx / VR 327xxx
        const val MSG_NAV_TTS_INTERRUPT = 262149
        const val MSG_NAV_TTS_FEED_END = 262150
        const val MSG_VR_AUDIO_INIT = 327682
        const val MSG_VR_AUDIO_DATA = 327683
        const val MSG_VR_AUDIO_STOP = 327684
        const val MSG_VR_AUDIO_FEED_END = 327688
        // MIC 控制命令 65xxx（走 channel 1）
        const val MSG_CMD_MIC_RECORD_WAKEUP_START = 65570
        const val MSG_CMD_MIC_RECORD_END = 65571
        const val MSG_CMD_MIC_RECORD_RECOG_START = 65572
    }

    const val MAX_FRAME = 67108864L   // 64MB（d/d.java 实证）

    /** 组外层帧：[channel:u32 BE][len:u32 BE][payload] */
    fun outer(channel: Int, payload: ByteArray): ByteArray {
        require(payload.size <= MAX_FRAME) { "frame too large: ${payload.size}" }
        val h = ByteArray(8)
        putU32BE(h, 0, channel.toLong())
        putU32BE(h, 4, payload.size.toLong())
        return h + payload
    }

    /**
     * 组内层 CarlifeCmdMessage——**双格式**（CarlifeCmdMessage.toByteArray 权威）：
     *  channel 1/6 → 8B 头：[len:u16 BE][reserved:u16 BE][serviceType:u32 BE@4]
     *  channel 2-5 → 12B 头：[len:u32 BE][_:u32 BE@4][serviceType:u32 BE@8]
     */
    fun inner(serviceType: Int, payload: ByteArray, channel: Int = Channel.CMD): ByteArray {
        if (channel == Channel.CMD || channel == Channel.TOUCH) {
            require(payload.size <= 0xFFFF) { "cmd message too large: ${payload.size}" }
            val h = ByteArray(8)
            putU16BE(h, 0, payload.size)
            putU16BE(h, 2, 0)            // reserved，恒 0
            putU32BE(h, 4, serviceType.toLong())
            return h + payload
        }
        require(payload.size <= 0x7FFFFFFF) { "cmd message too large: ${payload.size}" }
        val h = ByteArray(12)
        putU32BE(h, 0, payload.size.toLong())
        putU32BE(h, 4, 0)              // timestamp/保留（权威 toByteArray 写 0）
        putU32BE(h, 8, serviceType.toLong())
        return h + payload
    }

    /** 一条盒子命令：内层套外层，走 channel 1 */
    fun boxCmd(serviceType: Int, payload: ByteArray, channel: Int = Channel.CMD): ByteArray =
        outer(channel, inner(serviceType, payload, channel))

    /** 数据通道（2-5）裸发送路径：权威 ConnectSocket.writeData/AoaWriteTransport 实证
     *  ——视频/音频只发 [12B PCM/视频头][data]，**不套** CarlifeCmdMessage 内层头 */

    fun putU16BE(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 8).toByte()
        b[off + 1] = v.toByte()
    }

    fun putU32BE(b: ByteArray, off: Int, v: Long) {
        b[off] = (v ushr 24).toByte()
        b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte()
        b[off + 3] = v.toByte()
    }

    fun u32BE(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or
            ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or
            (b[off + 3].toLong() and 0xFF)

    fun u16BE(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)
}

/** 外层帧读取器（流式，处理 TCP 半包/粘包） */
class FrameReader {
    private val header = ByteArray(8)
    private var headerFilled = 0

    /**
     * 从输入流读一帧。返回 null 表示流结束。
     * @return Pair(channel, payload)
     */
    fun read(input: java.io.InputStream): Pair<Int, ByteArray>? {
        while (headerFilled < 8) {
            val n = input.read(header, headerFilled, 8 - headerFilled)
            if (n < 0) return null
            headerFilled += n
        }
        val channel = CarlifeFrame.u32BE(header, 0).toInt()
        val len = CarlifeFrame.u32BE(header, 4)
        // 权威 AoaReadTransport.java:57-61：channel∈[1,6] && 0≤size≤67108864（0 合法）
        require(channel in 1..6) { "bad channel: $channel" }
        require(len in 0..CarlifeFrame.MAX_FRAME) { "bad frame len: $len" }
        val payload = ByteArray(len.toInt())
        var filled = 0
        while (filled < payload.size) {
            val n = input.read(payload, filled, payload.size - filled)
            if (n < 0) return null
            filled += n
        }
        headerFilled = 0
        return channel to payload
    }
}

/** 内层消息解析结果 */
data class CmdMessage(val serviceType: Int, val data: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CmdMessage) return false
        return serviceType == other.serviceType && data.contentEquals(other.data)
    }

    override fun hashCode(): Int = 31 * serviceType + data.contentHashCode()
}

/**
 * 解析内层 CarlifeCmdMessage（双格式，CarlifeCmdMessage.fromByteArray 权威）。
 * @param channel 外层帧的 channel（决定 8B/12B 头）
 */
fun parseInner(payload: ByteArray, channel: Int): CmdMessage? {
    if (channel == CarlifeFrame.Channel.CMD || channel == CarlifeFrame.Channel.TOUCH) {
        if (payload.size < 8) return null
        val dataLen = CarlifeFrame.u16BE(payload, 0)
        if (8 + dataLen > payload.size) return null     // 半包/坏包：显式拒绝（零填充保护）
        val serviceType = CarlifeFrame.u32BE(payload, 4).toInt()
        val data = if (dataLen == 0) ByteArray(0) else payload.copyOfRange(8, 8 + dataLen)
        return CmdMessage(serviceType, data)
    }
    if (payload.size < 12) return null
    val dataLen = CarlifeFrame.u32BE(payload, 0)
    if (12 + dataLen > payload.size) return null
    val serviceType = CarlifeFrame.u32BE(payload, 8).toInt()
    val data = if (dataLen == 0L) ByteArray(0) else payload.copyOfRange(12, 12 + dataLen.toInt())
    return CmdMessage(serviceType, data)
}
