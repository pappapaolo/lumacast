package app.lumacast;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Hardware H.264/H.265 decode of the mirroring stream straight onto a Surface, lowest latency. */
final class VideoSink {
    interface SizeListener { void onDecodedSize(int w, int h); }

    private static final String TAG = "AirPlayTV";
    private final Object lock = new Object();
    private final ArrayList<byte[]> sinceKeyframe = new ArrayList<>();
    private final LinkedBlockingQueue<Integer> inputs = new LinkedBlockingQueue<>();
    private final HandlerThread thread = new HandlerThread("video-codec");
    private final Handler handler;
    private MediaCodec codec;
    private Surface surface;
    private int width = 1920, height = 1080;
    private boolean h265;
    private long pts;
    volatile SizeListener sizeListener;

    VideoSink() { thread.start(); handler = new Handler(thread.getLooper()); }

    void setFormat(int w, int h, boolean isH265) {
        synchronized (lock) {
            if (w > 0 && h > 0 && (w != width || h != height || isH265 != h265)) release();
            if (w > 0 && h > 0) { width = w; height = h; }
            h265 = isH265;
        }
    }

    void setSurface(Surface s) {
        synchronized (lock) {
            release();
            surface = s;
            if (s != null) for (byte[] f : sinceKeyframe) feed(f);
        }
    }

    void onFrame(byte[] d, boolean isH265) {
        synchronized (lock) {
            h265 = isH265;
            if (isKeyframe(d)) sinceKeyframe.clear();
            if (sinceKeyframe.size() < 900) sinceKeyframe.add(d);
            if (surface != null) feed(d);
        }
    }

    void stop() {
        synchronized (lock) { release(); sinceKeyframe.clear(); }
    }

    private void feed(byte[] d) {
        try {
            if (codec == null) {
                if (!isKeyframe(d)) return;
                start();
            }
            Integer idx = inputs.poll(50, TimeUnit.MILLISECONDS);
            if (idx == null) return; // decoder backed up: drop this frame
            ByteBuffer b = codec.getInputBuffer(idx);
            b.clear();
            if (d.length > b.capacity()) { codec.queueInputBuffer(idx, 0, 0, pts, 0); return; }
            b.put(d);
            pts += 16_666;
            codec.queueInputBuffer(idx, 0, d.length, pts, 0);
        } catch (Exception e) {
            Log.e(TAG, "video decode error", e);
            release();
        }
    }

    private void start() throws Exception {
        String mime = h265 ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
        MediaFormat f = MediaFormat.createVideoFormat(mime, width, height);
        f.setInteger(MediaFormat.KEY_MAX_WIDTH, Math.max(width, 1920));
        f.setInteger(MediaFormat.KEY_MAX_HEIGHT, Math.max(height, 1920));
        f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        f.setInteger(MediaFormat.KEY_PRIORITY, 0);
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024);
        codec = MediaCodec.createDecoderByType(mime);
        inputs.clear();
        codec.setCallback(new MediaCodec.Callback() {
            public void onInputBufferAvailable(MediaCodec c, int i) { inputs.offer(i); }
            public void onOutputBufferAvailable(MediaCodec c, int i, MediaCodec.BufferInfo info) {
                try { c.releaseOutputBuffer(i, true); } catch (Exception ignored) {}
            }
            public void onError(MediaCodec c, MediaCodec.CodecException e) { Log.e(TAG, "codec error", e); }
            public void onOutputFormatChanged(MediaCodec c, MediaFormat fmt) {
                int w = fmt.getInteger(MediaFormat.KEY_WIDTH), h = fmt.getInteger(MediaFormat.KEY_HEIGHT);
                if (fmt.containsKey("crop-right")) {
                    w = fmt.getInteger("crop-right") - fmt.getInteger("crop-left") + 1;
                    h = fmt.getInteger("crop-bottom") - fmt.getInteger("crop-top") + 1;
                }
                Log.i(TAG, "decoded size " + w + "x" + h);
                SizeListener l = sizeListener;
                if (l != null) l.onDecodedSize(w, h);
            }
        }, handler);
        codec.configure(f, surface, null, 0);
        codec.start();
        Log.i(TAG, "video decoder " + codec.getName() + " " + width + "x" + height);
    }

    private void release() {
        if (codec != null) {
            try { codec.stop(); } catch (Exception ignored) {}
            try { codec.release(); } catch (Exception ignored) {}
            codec = null;
            inputs.clear();
        }
    }

    /** Scan Annex-B NAL units for an IDR/SPS (H.264) or IRAP/VPS (H.265). */
    private boolean isKeyframe(byte[] d) {
        for (int i = 0; i + 4 < d.length; i++) {
            if (d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1) {
                int b = d[i + 3] & 0xff;
                if (h265) {
                    int t = (b >> 1) & 0x3f;
                    if (t == 32 || t == 33 || t == 19 || t == 20 || t == 21) return true;
                } else {
                    int t = b & 0x1f;
                    if (t == 5 || t == 7) return true;
                }
                i += 2;
            }
        }
        return false;
    }
}
