package com.seagull.boxcast.video

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.util.Log
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.seagull.boxcast.R
import com.seagull.boxcast.net.BoxConnection

/**
 * 投屏前台服务：持有 MediaProjection，驱动 H264Encoder + H264Sender。
 *
 * MediaProjection 必须在 Service 启动前由 Activity 授权取得，
 * 通过 [EXTRA_PROJECTION] 传入（MediaProjection 可跨组件传递）。
 */
class ScreenCastService : Service() {

    companion object {
        const val EXTRA_PROJECTION = "projection"
        const val ACTION_START = "com.seagull.boxcast.START_CAST"
        const val ACTION_STOP = "com.seagull.boxcast.STOP_CAST"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_FPS = "fps"
        const val EXTRA_USE_STANDARD_VIDEO = "use_standard_video"
        private const val CHANNEL_ID = "boxcast"
        private const val NOTIFY_ID = 1
    }

    private var encoder: H264Encoder? = null
    private var sender: H264Sender? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "BoxCast 投屏", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // START_STICKY 重启时 intent 为 null（MediaProjection 不可持久化）→ 直接退，别僵尸
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_START -> {
                startForeground(NOTIFY_ID, NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("BoxCast")
                    .setContentText("正在向盒子投屏")
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setOngoing(true)
                    .build())
                val mp = androidx.core.content.IntentCompat.getParcelableExtra(
                    intent, EXTRA_PROJECTION, android.media.projection.MediaProjection::class.java)
                if (mp == null) {
                    stopSelf(); return START_NOT_STICKY
                }
                val dm: android.hardware.display.DisplayManager =
                    getSystemService(android.hardware.display.DisplayManager::class.java)
                val conn = BoxConnectionHolder.connection ?: run { stopSelf(); return START_NOT_STICKY }

                // 编码参数（UI 可调；默认对齐逆向实证值 832x480@15fps/3.2Mbps）
                val w = intent.getIntExtra(EXTRA_WIDTH, H264Encoder.DEFAULT_WIDTH)
                val h = intent.getIntExtra(EXTRA_HEIGHT, H264Encoder.DEFAULT_HEIGHT)
                val br = intent.getIntExtra(EXTRA_BITRATE, H264Encoder.DEFAULT_BITRATE)
                val fps = intent.getIntExtra(EXTRA_FPS, H264Encoder.DEFAULT_FPS)

                sender = H264Sender(conn).apply { configure(w, h) }
                // 盒子点播驱动（不能主动发）：102405 → confirmH264Init；102407 → sendH264Start
                conn.onH264ListRequest = { sender?.confirmH264Init() }
                conn.onH264SendRequest = { req ->
                    sender?.sendH264Start(req.width, req.height, req.type, req.length.toInt(), req.frameType)
                }
                // 音频通道（channel 3）：INIT + 内部音频采集推送（44100/2/16 实证默认）
                audioSender = com.seagull.boxcast.audio.AudioChannel.Sender(conn).apply { init() }
                startAudioCapture(mp)
                // 车机 HUD 歌曲信息：轮询系统 MediaSession（需通知监听授权，未授权自动跳过）
                startMediaInfoLoop(conn)
                // 双视频路径：盒子点播（102409 裸帧，H264Sender）+
                // 标准 CarLife 推流（131073 + 12B 头 on channel 2，VideoSender）。
                // 标准路径走 VideoHandshake 状态机：等 HU 的 INIT(98312) 配编码器、
                // 回 INIT_DONE(98317)、等 START(98313) 才推流（VideoMsgHandler 实证）
                videoSender = VideoSender(conn)
                handshake = VideoHandshake(conn)
                // 真机日志实证：盒子 Video 端口接入率 1/40，视频全走 Cmd 通道点播（1024xx）→ 默认点播路径
                val useStandard = intent.getBooleanExtra(EXTRA_USE_STANDARD_VIDEO, false)
                videoSender?.sendEmptyForeground()

                handshake?.onConfigureEncoder = { hw, hh, hf ->
                    encoder?.changeFrameRate(hf)   // 按 HU 期望调整（分辨率同理由 reconfigure 处理）
                    handshake?.encWidth = hw; handshake?.encHeight = hh; handshake?.encFps = hf
                    handshake?.sendInitDone()
                }
                handshake?.onStartStreaming = { handshakeStarted = true }
                handshake?.onPauseStreaming = { handshakeStarted = false }
                handshake?.onFrameRateChange = { f -> encoder?.changeFrameRate(f) }
                conn.onVideoEncoderInit = { hw, hh, hf -> handshake?.onEncoderInit(hw, hh, hf) }
                conn.onVideoEncoderStart = { handshake?.onEncoderStart() }
                conn.onVideoEncoderPause = { handshake?.onEncoderPause() }
                conn.onVideoEncoderReset = { handshake?.onEncoderReset() }
                conn.onVideoFrameRateChange = { f -> handshake?.onFrameRateChange(f) }
                conn.onVersionMatch = { handshake?.onVersionMatch() }
                // 亮灭屏/前后台省电：车机熄屏或退后台 → 暂停推流+停采集；亮屏回前台 → 恢复
                conn.onScreenOff = { powerSavingPause() }
                conn.onVehicleBackground = { powerSavingPause() }
                conn.onScreenOn = { powerSavingResume() }
                conn.onVehicleForeground = { powerSavingResume() }
                conn.onHuInfo = { info ->
                    Log.i("ScreenCast", "HU info: $info")   // 屏幕/音频重协商触发源（DeviceInfo 27 字段）
                }
                conn.onPermissionRequest = { type ->
                    Log.w("ScreenCast", "HU requests permission: $type")   // TODO: UI 引导授权
                }

                encoder = H264Encoder(mp, dm) { data, key, pts ->
                    // 标准路径：必须等 HU START（98313）；点播路径：等 102407（sender.started）
                    // 省电模式（熄屏/车机退后台）下不推
                    val allow = !powerSaving &&
                        (if (useStandard) handshakeStarted else sender?.started == true)
                    if (allow) {
                        if (useStandard) videoSender?.sendFrame(data) else sender?.sendFrame(data, key, pts)
                    }
                    // 本地环回：同时喂 PipBoard 远端流槽位（验证解码渲染管线）
                    BoxConnectionHolder.streamSource?.feedFrame(data, key)
                }
                encoder?.start(w, h, br, fps)
            }
            ACTION_STOP -> {
                sender?.finish()
                encoder?.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    // ---------- 音频采集（AudioRecord → PCM 裸流 → channel 3） ----------

    private var audioRecord: android.media.AudioRecord? = null
    private var audioThread: Thread? = null
    private var audioSender: com.seagull.boxcast.audio.AudioChannel.Sender? = null
    private var videoSender: VideoSender? = null
    private var handshake: VideoHandshake? = null
    private var handshakeStarted = false

    /**
     * 内部音频采集（AudioPlaybackCapture，Android 10+ 公开 API）：
     * 直接用 MediaProjection 构建 capture config——**投屏授权即获内部音频权，无需 root/特权**。
     * 参数对齐官方 PCMMediaManager 实证默认：44100 / 双声道 / 16bit。
     * （官方媒体音频源是 app 播放的音乐流；投屏镜像场景用本 API 采全机内部声音）
     */
    private fun startAudioCapture(mp: android.media.projection.MediaProjection) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return
        val sr = com.seagull.boxcast.audio.AudioChannel.DEFAULT_SAMPLE_RATE
        val minBuf = android.media.AudioRecord.getMinBufferSize(
            sr,
            android.media.AudioFormat.CHANNEL_IN_STEREO,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
        )
        audioThread = Thread({
            android.os.Process.setThreadPriority(-8)
            runCatching {
                // AudioPlaybackCaptureConfiguration 经反射构建——该方法在部分 SDK jar
                // 不可见（seagull android.jar 与 CI jar 均无 Builder/setter 的公开签名），
                // 反射兼容 public/hidden 两种形态；Android 10+ 设备均支持
                val cfgCls = Class.forName("android.media.AudioPlaybackCaptureConfiguration")
                val captureConfig = cfgCls
                    .getDeclaredConstructor(android.media.projection.MediaProjection::class.java)
                    .newInstance(mp)
                val recBuilder = android.media.AudioRecord.Builder()
                    .setAudioFormat(
                        android.media.AudioFormat.Builder()
                            .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sr)
                            .setChannelMask(android.media.AudioFormat.CHANNEL_IN_STEREO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf * 2)
                runCatching {
                    recBuilder.javaClass
                        .getMethod("setAudioPlaybackCaptureConfiguration", cfgCls)
                        .invoke(recBuilder, captureConfig)
                }.onFailure { Log.w(TAG, "setAudioPlaybackCaptureConfiguration reflect fail", it) }
                val rec = recBuilder.build() as android.media.AudioRecord
                audioRecord = rec
                rec.startRecording()
                val buf = ByteArray(minBuf)
                while (audioThread?.isInterrupted == false) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) audioSender?.write(buf.copyOf(n))
                }
                rec.stop()
                rec.release()
            }.onFailure { Log.e("ScreenCast", "audio capture fail", it) }
        }, "AudioCapture").apply { start() }
    }

    // ---------- 车机 HUD 歌曲信息（MediaSession → MSG_CMD_MEDIA_INFO） ----------

    private var mediaInfoJob: java.util.Timer? = null

    private fun startMediaInfoLoop(conn: BoxConnection) {
        mediaInfoJob?.cancel()
        mediaInfoJob = java.util.Timer("MediaInfo").apply {
            scheduleAtFixedRate(object : java.util.TimerTask() {
                private var last: com.seagull.boxcast.net.MediaInfo.Info? = null
                override fun run() {
                    val cur = runCatching {
                        com.seagull.boxcast.net.MediaNotificationListener.currentTrack()
                    }.getOrNull() ?: return
                    if (!cur.equals(last)) {
                        last = cur
                        runCatching {
                            conn.sendRaw(com.seagull.boxcast.frame.CarlifeFrame.CmdId.MSG_CMD_MEDIA_INFO,
                                com.seagull.boxcast.net.MediaInfo.encode(cur))
                        }
                    }
                }
            }, 2000, 5000)
        }
    }

    /** 熄屏/退后台省电：暂停编码输出与音频采集（推流门控关闭，编码器保留热启） */
    private fun powerSavingPause() {
        powerSaving = true
        Log.i("ScreenCast", "power saving: pause streaming")
    }

    /** 亮屏/回前台恢复 */
    private fun powerSavingResume() {
        powerSaving = false
        videoSender?.sendEmptyForeground()   // 恢复时告知 HU（sendEmptyPacketForeground 实证）
        Log.i("ScreenCast", "power saving: resume streaming")
    }

    private var powerSaving = false

    override fun onDestroy() {
        sender?.finish()
        videoSender?.sendEmptyBackground()
        encoder?.stop()
        handshakeStarted = false
        audioThread?.interrupt()
        audioSender?.stop()
        mediaInfoJob?.cancel()
        runCatching { audioRecord?.stop(); audioRecord?.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/** 简单的全局连接持有（避免 Service 重建丢连接；生产可换 DI） */
object BoxConnectionHolder {
    var connection: BoxConnection? = null

    /** PipBoard 远端流槽位源（BoxStreamSource），由海鸥侧创建后注册 */
    var streamSource: com.seagull.boxcast.BoxStreamSource? = null
}
