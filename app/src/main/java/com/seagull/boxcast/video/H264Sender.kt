package com.seagull.boxcast.video

import android.util.Log
import com.seagull.boxcast.frame.CarlifeFrame
import com.seagull.boxcast.net.BoxConnection
import com.seagull.boxcast.proto.BoxH264Init
import com.seagull.boxcast.proto.BoxH264Send

/**
 * 把编码器输出的 H.264 帧按盒子协议发给盒子。
 *
 * 协议序列全实锤（com.baidu.carlife v8.9.5 BoxH264Manager）：
 *   盒子→手机 102405 BOX_H264LIST_REQUEST（请求初始化）
 *   手机→盒子 102406 BOX_H264LIST_RESPONSE = CarlifeBoxH264Init{
 *                  timeStamp(秒级i32), verifyValue="hellobox"(硬编码), supportPFrames=true}
 *                 → status: READY(2)
 *   盒子→手机 102407 BOX_SEND_H264_REQUEST = SendInfo{width,height,type,length,needPFrames}
 *                 （SM6375 芯片 1920x1140 降级）
 *   手机→盒子 102408 BOX_SEND_H264_START = SendInfo{width,height,type,length(i64),frameType}
 *   手机→盒子 102409 BOX_SEND_H264_DATA  = 裸 H.264 字节（无 protobuf 头！单段）
 *   手机→盒子 102410 BOX_SEND_H264_END   = SendInfo 同 START
 *   盒子→手机 102411 BOX_H264_DONE（全部完成）
 *
 * 注意：H.264 DATA 的原版实现是 CarlifeCmdMessage.setData(裸byte[])，单段直发，
 * 不是"元信息+裸流"两段式（旧版 AOA bulkTransfer 写法不适用于盒子 WiFi 通道）。
 */
class H264Sender(private val conn: BoxConnection) {

    @Volatile var started = false
        private set

    private var width = H264Encoder.DEFAULT_WIDTH
    private var height = H264Encoder.DEFAULT_HEIGHT

    fun configure(w: Int, h: Int) {
        width = w; height = h
    }

    /** 盒子 H.264 状态机（BoxH264Manager.checkStatus 实证） */
    @Volatile var status: Int = STATUS_DEFAULT
        private set

    /** START(102408) 实际发出的参数组——finish(102410) 必须复用同一组，
     *  否则盒子侧 BoxH264Manager 状态机校验失败（原用 configure() 的 UI 值） */
    private var startWidth = H264Encoder.DEFAULT_WIDTH
    private var startHeight = H264Encoder.DEFAULT_HEIGHT
    private var startType = 0
    private var startFrameType = 0
    private var startLength = 0L

    /**
     * 响应盒子 102405（H264LIST_REQUEST）→ 发 102406。
     * verifyValue 硬编码 "hellobox"（confirmH264Init 实证）。
     */
    fun confirmH264Init() {
        val init = BoxH264Init(
            timeStamp = System.currentTimeMillis() / 1000,   // 秒级
            verifyValue = VERIFY_VALUE_HELLOBOX,
            supportPFrames = true,
        )
        conn.sendRaw(CarlifeFrame.CmdId.BOX_H264LIST_RESPONSE, init.encode())   // 102406！
        status = STATUS_READY
        Log.i(TAG, "confirmH264Init -> 102406 (READY)")
    }

    /**
     * 响应盒子 102407（SEND_H264_REQUEST）→ 发 102408 START。
     * @param reqWidth/reqHeight 盒子要求的宽高（来自盒子的 SendInfo）
     */
    fun sendH264Start(reqWidth: Int, reqHeight: Int, type: Int, length: Int, framesType: Int) {
        val info = BoxH264Send(
            type = type,
            frameType = framesType,
            width = reqWidth,
            length = length.toLong(),
            height = reqHeight,
        ).encode()
        conn.sendRaw(CarlifeFrame.CmdId.BOX_SEND_H264_START, info)
        started = true
        startWidth = reqWidth; startHeight = reqHeight
        startType = type; startFrameType = framesType; startLength = length.toLong()
        Log.i(TAG, "sendH264Start -> 102408 ${reqWidth}x$reqHeight")
    }

    /** 102409：发一帧裸 H.264（单段直发，无 protobuf 头） */
    fun sendFrame(h264: ByteArray, keyFrame: Boolean, ptsUs: Long) {
        if (!started) return
        conn.sendRaw(CarlifeFrame.CmdId.BOX_SEND_H264_DATA, h264)
    }

    /** 收尾便捷方法（END，复用 START 参数组——盒子状态机校验） */
    fun finish() = sendH264End(startWidth, startHeight, startType, startLength.toInt(), startFrameType)

    /** 102410：收尾（END，SendInfo 同 START 结构） */
    fun sendH264End(width: Int, height: Int, type: Int, length: Int, framesType: Int) {
        if (!started) return
        val info = BoxH264Send(
            type = type, frameType = framesType,
            width = width, length = length.toLong(), height = height,
        ).encode()
        conn.sendRaw(CarlifeFrame.CmdId.BOX_SEND_H264_END, info)
        started = false
        Log.i(TAG, "sendH264End -> 102410")
    }

    companion object {
        private const val TAG = "H264Sender"
        const val VERIFY_VALUE_HELLOBOX = "hellobox"     // confirmH264Init 硬编码实证
        const val STATUS_DEFAULT = -1
        const val STATUS_CONNECTED = 1
        const val STATUS_READY = 2
        const val FRAME_TYPE_I = 1                       // TODO-CALIBRATE: I/P 值待抓包
        const val FRAME_TYPE_P = 2
    }
}
