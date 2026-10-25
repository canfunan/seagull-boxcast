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
        encoderThread = HandlerThread("H264Encoder").apply { start() }
        encoderHandler = Handler(encoderThread!!.looper)

        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)   // 秒级；精确 IDR 间隔用 requestKeyFrame
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
        // Android 13+：同一 projection 实例只能 createVirtualDisplay 一次
        // （SecurityException: Don't take multiple captures on the same instance）
        // → VD 缓存复用（免重复授权的另一半）；缓存失效才新建
        val cached = com.seagull.boxcast.video.BoxConnectionHolder.virtualDisplay
        virtualDisplay = if (cached != null && cached.display != null) {
            Log.i(TAG, "复用缓存 VirtualDisplay（免重新 capture）")
            // 复用 VD：需把编码器 surface 挂上去——VD 的 surface 不能热换，
            // 因此复用时必须同参数（宽度/高度变化仍走重建路径）
            if (cached.display.width == width && cached.display.height == height) {
                resizeIfNeeded(width, height)
                cached
            } else {
                com.seagull.boxcast.video.BoxConnectionHolder.virtualDisplay = null
                cached.release()
                createVD(mediaProjection, width, height, inputSurface, encoderHandler)
            }
        } else {
            createVD(mediaProjection, width, height, inputSurface, encoderHandler)
        }
        com.seagull.boxcast.video.BoxConnectionHolder.virtualDisplay = virtualDisplay
        running = true
        currentFps = fps
        currentWidth = width
        currentHeight = height
        currentBitrate = bitrate
        Log.i(TAG, "encoder started ${width}x$height @${fps}fps ${bitrate}bps")
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
                onFrame(out, key, info.presentationTimeUs)
                codec.releaseOutputBuffer(index, false)
            } catch (e: Exception) {
                Log.e(TAG, "output buffer", e)
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "codec error", e)
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

    /** 复用 VD 时参数对齐（分辨率没变就不动） */
    private fun resizeIfNeeded(w: Int, h: Int) { /* no-op：同参数无需 resize */ }

    fun stop() {
        running = false
        runCatching { virtualDisplay?.release() }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { inputSurface?.release() }
        runCatching { encoderThread?.quitSafely() }
        virtualDisplay = null; codec = null; inputSurface = null
        encoderThread = null; encoderHandler = null
    }
}
