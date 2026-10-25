package com.seagull.boxcast.video

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * H.264 截屏编码器（MediaProjection → VirtualDisplay → MediaCodec）。
 *
 * 默认参数对齐逆向实证（2021 h/g.java + 2026 Configs）：
 *   832x480 @ 15fps，3.2Mbps，video/avc，IDR 间隔 300ms
 * 可通过 [reconfigure] 按盒子 Hello.isHuRate / 102401 协商结果动态调整。
 */
class H264Encoder(
    private val mediaProjection: MediaProjection,
    private val displayManager: DisplayManager,
    private val onFrame: (ByteArray, isKeyFrame: Boolean, ptsUs: Long) -> Unit,
) {
    companion object {
        private const val TAG = "H264Encoder"
        const val DEFAULT_WIDTH = 832        // h/g.java: f374a = 832
        const val DEFAULT_HEIGHT = 480       // h/g.java: b = 480
        const val DEFAULT_FPS = 30           // 真机日志实证 sendInitFrameRate=30（h/g.java 旧默认 15）
        const val DEFAULT_BITRATE = 3_276_800 // h/g.java: d = 3276800
        const val I_FRAME_INTERVAL_MS = 300  // 2026 FEATURE_CONFIG_I_FRAME_INTERVAL=300
        const val MIME = "video/avc"         // t.smali: "video/avc"
    }

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoderThread: HandlerThread? = null
    private var encoderHandler: Handler? = null

    @Volatile var running = false
        private set

    fun start(
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT,
        bitrate: Int = DEFAULT_BITRATE,
        fps: Int = DEFAULT_FPS,
    ) {
        // 分辨率对齐（公签 u.java:29-47 权威：w%16==0 && h%8==0——
        // 硬编解码器对奇数尺寸兼容性差）
        val w = width - (width % 16)
        val h = height - (height % 8)
        encoderThread = HandlerThread("H264Encoder").apply { start() }
        encoderHandler = Handler(encoderThread!!.looper)

        val format = MediaFormat.createVideoFormat(MIME, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)   // 秒级；精确 IDR 间隔用 requestKeyFrame
            // 公签 h/f.java:118-120：静止画面保持编码（息屏/静态桌面不断流）
            setInteger(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 3_000_000)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            // 官方 Recorder 实证：Baseline profile + level 256（部分盒子/车机只解 Baseline，
            // 不设可能落到 High 导致花屏）
            setInteger(MediaFormat.KEY_PROFILE, 1)            // AVCProfileBaseline
            setInteger(MediaFormat.KEY_LEVEL, 256)
        }

        codec = MediaCodec.createEncoderByType(MIME).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = createInputSurface()
            setCallback(codecCallback, encoderHandler)
            start()
        }

        // MediaProjection → VirtualDisplay("BoxCast") → 编码器 input surface。
        // **不做 VD 缓存复用**（复盘实证 100% 黑屏）：VD 的 surface 在创建时绑定，
        // release 后 getDisplay() 仍非 null → 复用死 VD 新 surface 没挂上去；
        // 且同 projection 实例二次 createVirtualDisplay 在 Android 13+ 抛
        // SecurityException。每次 start 都新建 VD；projection 失效则重新授权。
        val mp = mediaProjection
        if (mp == null) {
            Log.e(TAG, "no media projection, abort start")
            stop()
            return
        }
        val vd = runCatching {
            createVD(mp, w, h, inputSurface, encoderHandler)
        }.onFailure { Log.e(TAG, "createVirtualDisplay fail（projection 可能已失效，需重新授权）: ${it.message}") }.getOrNull()
        if (vd == null) {
            stop()
            return
        }
        virtualDisplay = vd
        com.seagull.boxcast.video.BoxConnectionHolder.virtualDisplay = vd
        running = true
        currentFps = fps
        currentWidth = w
        currentHeight = h
        currentBitrate = bitrate
        Log.i(TAG, "encoder started ${w}x$h (req ${width}x$height) @${fps}fps ${bitrate}bps")
    }

    @Volatile var currentFps = DEFAULT_FPS
        private set

    private val codecCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            // Surface 输入模式不需要
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                val buf: ByteBuffer = codec.getOutputBuffer(index) ?: run {
                    codec.releaseOutputBuffer(index, false); return
                }
                val out = ByteArray(info.size)
                buf.position(info.offset)
                buf.get(out, 0, info.size)
                // CODEC_CONFIG（SPS/PPS）也发出：解码端（BoxStreamSource/盒子）需要它起解，
                // 丢弃会导致花屏/黑屏。当关键帧处理，发送端原样转发即可。
                val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 ||
                    info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                try {
                    onFrame(out, key, info.presentationTimeUs)
                } finally {
                    runCatching { codec.releaseOutputBuffer(index, false) }   // finally：异常也释放
                }
            } catch (e: Exception) {
                Log.e(TAG, "output buffer", e)
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "codec error", e)
            running = false          // codec 死后不会再有帧：必须标记，否则永远黑屏
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            Log.i(TAG, "format: $format")
        }
    }

    /** 请求立即 IDR（SPS/PPS 重发，对应 ailink.idr_insert_sps 策略） */
    fun requestKeyFrame() {
        runCatching { codec?.setParameters(android.os.Bundle().apply {
            putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
        }) }
    }

    /**
     * 运行时改帧率（对齐协议 MSG_CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE=98316，
     * HU 下发 → Recorder.changeFrameRate → 98317 DONE 闭环）。
     * MediaCodec 不支持热改帧率，走 reconfigure（旧 codec 重建，短暂花屏）。
     */
    fun changeFrameRate(fps: Int) {
        Log.i(TAG, "changeFrameRate -> $fps (98316 协商)")
        reconfigure(currentWidth, currentHeight, bitrate = currentBitrate, fps = fps)
    }

    @Volatile var currentWidth = DEFAULT_WIDTH
        private set
    @Volatile var currentHeight = DEFAULT_HEIGHT
        private set
    @Volatile var currentBitrate = DEFAULT_BITRATE
        private set

    /** 102401 改帧宽高（盒子侧协商） */
    @Synchronized
    fun reconfigure(width: Int, height: Int, bitrate: Int = currentBitrate, fps: Int = currentFps) {
        Log.i(TAG, "reconfigure -> ${width}x$height @${fps}fps ${bitrate}bps")
        stop()
        start(width, height, bitrate, fps)
    }

    private fun createVD(
        mp: android.media.projection.MediaProjection,
        w: Int, h: Int,
        surface: android.view.Surface?,
        handler: android.os.Handler?,
    ): android.hardware.display.VirtualDisplay? {
        if (surface == null || handler == null) return null
        return mp.createVirtualDisplay("BoxCast", w, h, 1,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, handler)
    }

    fun stop() {
        running = false
        runCatching { virtualDisplay?.release() }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { inputSurface?.release() }
        runCatching { encoderThread?.quitSafely() }
        val vd = virtualDisplay
        virtualDisplay = null; codec = null; inputSurface = null
        encoderThread = null; encoderHandler = null
        // 必须同步清 Holder：否则 Holder 变野指针，下次 start 复用已 release 的 VD（黑屏）。
        // 只清自己那条（Holder 里若是别的实例——双 encoder 异常场景——不动它）
        val held = com.seagull.boxcast.video.BoxConnectionHolder.virtualDisplay
        if (held == null || held === vd) {
            com.seagull.boxcast.video.BoxConnectionHolder.virtualDisplay = null
        }
    }
}
