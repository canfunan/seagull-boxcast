package com.seagull.boxcast.video

import android.util.Log
import com.seagull.boxcast.frame.CarlifeFrame
import com.seagull.boxcast.frame.CmdId
import com.seagull.boxcast.net.BoxConnection
import com.seagull.boxcast.proto.BoxH264Init
import com.seagull.boxcast.proto.BoxH264Send

/**
 * 把编码器输出的 H.264 帧按盒子协议发给盒子。
 *
 * 逆向实证的协议序列（CommonParams 1024xx 段）：
 *   102407 BOX_SEND_H264_REQUEST  手机→盒子 请求发送
 *   102408 BOX_SEND_H264_START    BoxH264Init{timeStamp, verifyValue, supportPFrames}
 *   102409 BOX_SEND_H264_DATA     BoxH264Send{...} + H.264 裸帧（分片）
 *   102410 BOX_SEND_H264_END      结束
 *   102411 BOX_H264_DONE          完成
 *
 * TODO-CALIBRATE:
 *   - H.264 帧是放 BoxH264Send 的某个 bytes 字段，还是作为外层帧 payload 直接跟在
 *     内层消息后面（2021 AOA 的 bulkTransfer 两段写模式），需抓包确认。
 *     本实现采用"内层消息 + 紧接着的原始 H.264 Annex-B 数据"（两段式），
 *     与 d/o.java 的 [header][payload] 双段写一致。
 *   - [chunkSize] 分片大小需实测（USB 模式 256KB 分片，WiFi 可更大）。
 */
class H264Sender(private val conn: BoxConnection) {

    companion object {
        private const val TAG = "H264Sender"
        const val CHUNK_SIZE = 64 * 1024        // TODO-CALIBRATE: 分片大小
        const val FRAME_TYPE_I = 1              // TODO-CALIBRATE
        const val FRAME_TYPE_P = 2
    }

    @Volatile var started = false
        private set

    private var width = H264Encoder.DEFAULT_WIDTH
    private var height = H264Encoder.DEFAULT_HEIGHT

    fun configure(w: Int, h: Int) {
        width = w; height = h
    }

    /** 102407 + 102408：握手开始 */
    fun requestStart(verifyValue: String, supportPFrames: Boolean) {
        conn.sendRaw(CmdId.BOX_SEND_H264_REQUEST, ByteArray(0))
        conn.sendRaw(CmdId.BOX_SEND_H264_START,
            BoxH264Init(timeStamp = System.currentTimeMillis(),
                verifyValue = verifyValue,
                supportPFrames = supportPFrames).encode())
        started = true
        Log.i(TAG, "h264 start requested")
    }

    /** 102409：发一帧（头 + Annex-B 数据） */
    fun sendFrame(h264: ByteArray, keyFrame: Boolean, ptsUs: Long) {
        if (!started) return
        val header = BoxH264Send(
            type = if (keyFrame) FRAME_TYPE_I else FRAME_TYPE_P,
            frameType = if (keyFrame) FRAME_TYPE_I else FRAME_TYPE_P,
            width = width,
            timeStamp = ptsUs / 1000,
            needPFrames = !keyFrame,
            height = height,
        ).encode()
        // 两段式：内层(serviceType=102409)+ 元信息 protobuf + 裸 H.264
        conn.sendRawTwoPart(CmdId.BOX_SEND_H264_DATA, header, h264)
    }

    /** 102410 + 102411：收尾 */
    fun finish() {
        if (!started) return
        conn.sendRaw(CmdId.BOX_SEND_H264_END, ByteArray(0))
        conn.sendRaw(CmdId.BOX_H264_DONE, ByteArray(0))
        started = false
        Log.i(TAG, "h264 finished")
    }
}
