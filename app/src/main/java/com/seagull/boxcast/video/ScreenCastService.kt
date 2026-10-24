package com.seagull.boxcast.video

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
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
                val mp = intent.getParcelableExtra<MediaProjection>(EXTRA_PROJECTION)
                if (mp == null) {
                    stopSelf(); return START_NOT_STICKY
                }
                val dm = getSystemService(DisplayManager::class.java)
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
                // 音频通道（channel 3）：INIT + AudioRecord 采集推送（44100/2/16 实证默认）
                audioSender = com.seagull.boxcast.audio.AudioChannel.Sender(conn).apply { init() }
                startAudioCapture()
                encoder = H264Encoder(mp, dm) { data, key, pts ->
                    sender?.sendFrame(data, key, pts)
                    // 本地环回：同时喂 PipBoard 远端流槽位（验证解码渲染管线；
                    // 盒子来流协议实测后改为 BoxConnection 分发喂入）
                    streamSource?.feedFrame(data, key)
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

    private fun startAudioCapture() {
        val sr = com.seagull.boxcast.audio.AudioChannel.DEFAULT_SAMPLE_RATE
        val minBuf = android.media.AudioRecord.getMinBufferSize(
            sr,
            android.media.AudioFormat.CHANNEL_IN_STEREO,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
        )
        audioThread = Thread({
            android.os.Process.setThreadPriority(-8)
            runCatching {
                val rec = android.media.AudioRecord(
                    android.media.MediaRecorder.AudioSource.MIC,   // TODO-CALIBRATE: 音源按场景换（投屏内录用 REMOTE_SUBMIX 需特权）
                    sr,
                    android.media.AudioFormat.CHANNEL_IN_STEREO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2,
                )
                audioRecord = rec
                rec.startRecording()
                val buf = ByteArray(minBuf)
                while (audioThread?.isInterrupted == false) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) audioSender?.write(buf.copyOf(n))
                }
                rec.stop()
                rec.release()
            }
        }, "AudioCapture").apply { start() }
    }

    override fun onDestroy() {
        sender?.finish()
        encoder?.stop()
        audioThread?.interrupt()
        audioSender?.stop()
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
