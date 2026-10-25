package com.seagull.boxcast.video

import com.seagull.boxcast.frame.CarlifeFrame
import com.seagull.boxcast.net.BoxConnection

/**
 * 标准视频推流（复刻官方 Recorder.sendVideoData + ConnectManager.writeVideoData 实证）。
 *
 * 与盒子点播路径（H264Sender，102409 裸帧）是**两条独立路径**：
 *   本类 = 标准 CarLife 视频流：channel 2 + 12B 头 + H.264 两段写
 *   MSG_VIDEO_DATA(131073) 走 VideoSocket(8240)/AOA channel 2
 *
 * 12B 头（Recorder.packageHead 实证，大端）：
 *   字节 0-3  dataLen（H.264 长度）
 *   字节 4-7  timeStamp（秒级 currentTimeMillis/1000）
 *   字节 8-11 固定 00 02 00 01（前台数据）/ 00 02 00 02（后台空包）
 *
 * 空包机制（sendEmptyPacketForeground/Background 实证）：dataLen=0，
 * type=01 前台 / 02 后台——手机退后台时发空包告知车机（不是走消息）。
 *
 * 双向暂停（Recorder 实证）：pauseFromHeadUnit / pauseFromMobile 双标志仲裁。
 */
class VideoSender(private val conn: BoxConnection) {

    companion object {
        private const val TAG = "VideoSender"
        private const val TYPE_FOREGROUND = 1     // 00 02 00 01
        private const val TYPE_BACKGROUND = 2     // 00 02 00 02
    }

    @Volatile var paused = false
        private set
    private var pausedByHeadUnit = false
    private var pausedByMobile = false

    /** 发一帧：[12B 业务头][H.264] 直写视频通道（权威 writeVideoData(head,12,data)） */
    fun sendFrame(h264: ByteArray) {
        if (pausedByHeadUnit || pausedByMobile) return
        val head = buildHead(h264.size, TYPE_FOREGROUND)
        conn.sendRawData(CarlifeFrame.Channel.VIDEO, head, h264)
    }

    /** 前台空包（恢复推送/首连告知） */
    fun sendEmptyForeground() {
        conn.sendRawData(CarlifeFrame.Channel.VIDEO,
            buildHead(0, TYPE_FOREGROUND), ByteArray(0))
    }

    /** 后台空包（退后台告知车机） */
    fun sendEmptyBackground() {
        conn.sendRawData(CarlifeFrame.Channel.VIDEO,
            buildHead(0, TYPE_BACKGROUND), ByteArray(0))
    }

    /** 车机让暂停（MSG_CMD_VIDEO_ENCODER_PAUSE） */
    fun pauseFromHeadUnit() { pausedByHeadUnit = true; updatePaused() }

    /** 车机让恢复（MSG_CMD_VIDEO_ENCODER_START） */
    fun resumeFromHeadUnit() { pausedByHeadUnit = false; updatePaused() }

    /** 手机端退后台 */
    fun pauseFromMobile() { pausedByMobile = true; updatePaused(); sendEmptyBackground() }

    /** 手机端回前台 */
    fun resumeFromMobile() { pausedByMobile = false; updatePaused(); sendEmptyForeground() }

    private fun updatePaused() { paused = pausedByHeadUnit || pausedByMobile }

    private fun buildHead(len: Int, type: Int): ByteArray {
        val ts = (System.currentTimeMillis() / 1000).toInt()
        return ByteArray(12).also { h ->
            CarlifeFrame.putU32BE(h, 0, len.toLong())
            CarlifeFrame.putU32BE(h, 4, ts.toLong())
            h[8] = 0
            h[9] = 2
            h[10] = 0
            h[11] = type.toByte()
        }
    }
}
