package com.seagull.boxcast;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;
import com.seagull.carlauncher.CanvasSource;

/**
 * BoxCast 远端流画面源 —— 实现海鸥桌面的 CanvasSource 接口，
 * 把解码后的 H.264 帧渲染进 PipBoard 槽位的 Surface（画中画挂载管线）。
 *
 * 管线：H.264 裸流（ANX-B）→ MediaCodec 解码 → Surface 直显。
 *
 * 输入源两种模式：
 *   1) 本地环回（当前默认）：ScreenCastService 的编码器输出同时喂进来，
 *      用于在无真盒子时验证「解码→渲染→回传触摸」整条管线；
 *   2) 盒子来流（协议实测后替换）：BoxConnection 收到盒子的 H.264 数据
 *      直接调 feedFrame()。耘电盒子协议（1024xx 段）静态未见盒子→手机
 *      视频消息 ID，实测到 serviceType 后只需改 BoxConnection 的分发一行。
 */
public final class BoxStreamSource implements CanvasSource {

    private static final String TAG = "BoxStreamSource";
    private static final String MIME = "video/avc";

    // 注：实例获取走 BoxConnectionHolder.streamSource（海鸥侧创建后注册）

    private final String name;
    private MediaCodec decoder;
    private volatile boolean ready = false;
    private volatile boolean configured = false;

    public BoxStreamSource(String name) {
        this.name = name;
    }

    @Override
    public int displayId() {
        return -1;   // 非 VirtualDisplay 源
    }

    /**
     * 编码器回调线程专用：**只入队不阻塞**（原 feedFrame 在编码回调线程同步
     * dequeueInputBuffer(10ms) → 拖慢输出消费 → VD 丢帧）。
     * 独立解码线程消费；队列满丢最旧帧。
     */
    public static void postFrame(byte[] data, boolean key) {
        BoxStreamSource self = com.seagull.boxcast.video.BoxConnectionHolder.streamSource;
        if (self == null || !self.ready) return;
        self.frameQueue.offer(new Frame(data, key));
    }

    private static final class Frame {
        final byte[] data; final boolean key;
        Frame(byte[] d, boolean k) { data = d; key = k; }
    }

    private final java.util.concurrent.LinkedBlockingQueue<Frame> frameQueue =
            new java.util.concurrent.LinkedBlockingQueue<>(8);
    private volatile boolean decodeLoopRunning = false;

    /** 解码消费循环（attachSurface 时起） */
    private void startDecodeLoop() {
        if (decodeLoopRunning) return;
        decodeLoopRunning = true;
        Thread t = new Thread(() -> {
            while (decodeLoopRunning) {
                Frame f = null;
                try { f = frameQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS); }
                catch (InterruptedException e) { break; }
                if (f == null) continue;
                try { feedFrame(f.data, f.key); } catch (Exception e) { Log.e(TAG, "decode loop", e); }
            }
        }, "BoxDecode");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public boolean ready() {
        return ready;
    }

    @Override
    public synchronized boolean attachSurface(Surface surface, int w, int h, int dpi) {
        try {
            releaseDecoder();   // 二次 attach 先释放旧 decoder（MediaCodec 泄漏修复）
            decoder = MediaCodec.createDecoderByType(MIME);
            MediaFormat fmt = MediaFormat.createVideoFormat(MIME, w, h);
            decoder.configure(fmt, surface, null, 0);
            decoder.start();
            configured = true;
            ready = true;
            startDecodeLoop();
            Log.i(TAG, name + " attachSurface " + w + "x" + h + " decoder started");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "attachSurface fail", e);
            releaseDecoder();
            return false;
        }
    }

    @Override
    public synchronized void detachSurface() {
        decodeLoopRunning = false;
        frameQueue.clear();
        // 只断渲染，不解码器（快速切回时省一次 configure）
        Log.i(TAG, name + " detachSurface");
    }

    /** 喂一帧 H.264（ANX-B，含 start code） */
    public synchronized void feedFrame(byte[] data, boolean keyFrame) {
        if (!ready || !configured || decoder == null) return;
        try {
            int in = decoder.dequeueInputBuffer(10000);
            if (in >= 0) {
                java.nio.ByteBuffer buf = decoder.getInputBuffer(in);
                buf.clear();
                buf.put(data);
                decoder.queueInputBuffer(in, 0, data.length,
                        System.nanoTime() / 1000,
                        keyFrame ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
            }
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int out;
            while ((out = decoder.dequeueOutputBuffer(info, 0)) >= 0) {
                decoder.releaseOutputBuffer(out, true);   // true = 渲染到 Surface
            }
        } catch (Exception e) {
            Log.w(TAG, "feedFrame: " + e.getMessage());
        }
    }

    public synchronized void release() {
        ready = false;
        releaseDecoder();
    }

    private void releaseDecoder() {
        configured = false;
        MediaCodec c = decoder;
        if (c != null) {
            try {
                c.stop();
                c.release();
            } catch (Exception ignore) {
            }
        }
        decoder = null;
    }
}
