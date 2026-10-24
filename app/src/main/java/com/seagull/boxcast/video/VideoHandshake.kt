package com.seagull.boxcast.video

import com.seagull.boxcast.frame.CarlifeFrame
import com.seagull.boxcast.net.BoxConnection
import com.seagull.boxcast.proto.Wire

/**
 * 视频编码器握手（复刻 VideoMsgHandler 实证流程）：
 *
 *   HU → 手机：MSG_CMD_VIDEO_ENCODER_INIT(98312)  {HU 屏幕参数}
 *   手机：initMediaCodec（按 INIT 参数配编码器；未初始化时）
 *   手机 → HU：MSG_CMD_VIDEO_ENCODER_INIT_DONE(98317) {CarlifeVideoEncoderInfo{width,height,frameRate}}
 *   HU → 手机：MSG_CMD_VIDEO_ENCODER_START(98313)   → resumeFromHeadUnit + startRecord 推流
 *   HU → 手机：PAUSE(98314)/RESET(98315)/FRAME_RATE_CHANGE(98316)
 *
 * 关键差异（对照我们旧实现）：**必须等 HU 的 INIT 才配编码器、等 START 才推流**，
 * 而不是连上就按 UI 静态参数推——HU 会按 INIT_DONE 的参数准备解码器。
 */
class VideoHandshake(private val conn: BoxConnection) {

    companion object {
        /** CarlifeVideoEncoderInfo（sendVideoSize 实证：1=width 2=height 3=frameRate） */
        fun encoderInfo(width: Int, height: Int, frameRate: Int): ByteArray =
            Wire.Writer().apply {
                int32(1, width)
                int32(2, height)
                int32(3, frameRate)
            }.toByteArray()
    }

    /** 握手状态 */
    enum class Phase { IDLE, INITED, STARTED, PAUSED }

    @Volatile var phase = Phase.IDLE
        private set

    /** 编码器实际参数（INIT_DONE 回报给 HU） */
    @Volatile var encWidth = H264Encoder.DEFAULT_WIDTH
    @Volatile var encHeight = H264Encoder.DEFAULT_HEIGHT
    @Volatile var encFps = H264Encoder.DEFAULT_FPS

    var onConfigureEncoder: ((width: Int, height: Int, fps: Int) -> Unit)? = null
    var onStartStreaming: (() -> Unit)? = null
    var onPauseStreaming: (() -> Unit)? = null
    var onFrameRateChange: ((fps: Int) -> Unit)? = null

    /** HU 发 INIT：配编码器（或已初始化则直接回 INIT_DONE） */
    fun onEncoderInit(huWidth: Int, huHeight: Int, huFps: Int) {
        if (phase == Phase.IDLE) {
            // 按 HU 期望配置编码器（HU 屏幕参数优先，回调 ScreenCastService 重建编码器）
            onConfigureEncoder?.invoke(huWidth, huHeight, huFps)
        }
        sendInitDone()
    }

    /** 回 INIT_DONE（带实际编码参数） */
    fun sendInitDone() {
        conn.sendRaw(CarlifeFrame.CmdId.MSG_CMD_VIDEO_ENCODER_INIT_DONE,
            encoderInfo(encWidth, encHeight, encFps))
        phase = Phase.INITED
    }

    /** HU 发 START：开始推流 */
    fun onEncoderStart() {
        phase = Phase.STARTED
        onStartStreaming?.invoke()
    }

    fun onEncoderPause() {
        phase = Phase.PAUSED
        onPauseStreaming?.invoke()
    }

    fun onEncoderReset() {
        phase = Phase.IDLE
    }

    fun onFrameRateChange(fps: Int) {
        onFrameRateChange?.invoke(fps)
        conn.sendRaw(CarlifeFrame.CmdId.MSG_CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE,
            encoderInfo(encWidth, encHeight, fps))
    }

    /** 版本匹配后：回统计 + 视频心跳（handleProtocolVersionMatch 实证） */
    fun onVersionMatch() {
        conn.sendRaw(CarlifeFrame.CmdId.MSG_CMD_STATISTIC_INFO,
            com.seagull.boxcast.net.StatisticsInfo.encode(
                com.seagull.boxcast.net.StatisticsInfo.Info(
                    cuid = java.util.UUID.randomUUID().toString(),
                    versionName = "BoxCast",
                    versionCode = 1,
                    channel = "boxcast",
                    connectCount = 1,
                    connectSuccessCount = 1,
                )
            ))
        conn.sendRaw(CarlifeFrame.CmdId.MSG_VIDEO_HEARTBEAT, ByteArray(0))
    }
}
