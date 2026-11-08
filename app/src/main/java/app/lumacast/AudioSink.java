package app.lumacast;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;

import java.nio.ByteBuffer;

/** AirPlay audio: ALAC (ct=2, music) via bundled decoder, AAC-LC (4) / AAC-ELD (8, mirroring) via MediaCodec. */
final class AudioSink {
    private static final String TAG = "AirPlayTV";
    private AudioTrack track;
    private MediaCodec aac;
    private int ct = -1;
    private float volume = 1f;
    private final byte[] pcm = new byte[16384];
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

    synchronized void setFormat(int newCt) {
        release();
        ct = newCt;
        try {
            int min = AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(44100)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(min * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build();
            track.setVolume(volume);
            track.play();
            if (ct == 2) {
                Native.alacInit();
            } else {
                MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2);
                byte[] csd = ct == 8 ? new byte[]{(byte) 0xF8, (byte) 0xE8, 0x50, 0x00} : new byte[]{0x12, 0x10};
                f.setByteBuffer("csd-0", ByteBuffer.wrap(csd));
                f.setInteger(MediaFormat.KEY_AAC_PROFILE, ct == 8
                        ? MediaCodecInfo.CodecProfileLevel.AACObjectELD : MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                aac = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
                aac.configure(f, null, null, 0);
                aac.start();
            }
            Log.i(TAG, "audio started ct=" + ct);
        } catch (Exception e) {
            Log.e(TAG, "audio init failed", e);
            release();
        }
    }

    synchronized void onData(byte[] d, int dataCt) {
        if (track == null || dataCt != ct) setFormat(dataCt);
        if (track == null) return;
        try {
            if (ct == 2) {
                int n = Native.alacDecode(d, d.length, pcm);
                if (n > 0) track.write(pcm, 0, n);
            } else if (aac != null) {
                int in = aac.dequeueInputBuffer(10_000);
                if (in >= 0) {
                    ByteBuffer b = aac.getInputBuffer(in);
                    b.clear();
                    b.put(d);
                    aac.queueInputBuffer(in, 0, d.length, 0, 0);
                }
                int out;
                while ((out = aac.dequeueOutputBuffer(info, 0)) >= 0) {
                    ByteBuffer o = aac.getOutputBuffer(out);
                    int n = Math.min(info.size, pcm.length);
                    o.position(info.offset);
                    o.get(pcm, 0, n);
                    aac.releaseOutputBuffer(out, false);
                    if (n > 0) track.write(pcm, 0, n);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "audio decode error", e);
            release();
        }
    }

    /** AirPlay volume is -30..0 dB, -144 = mute. */
    synchronized void setVolume(float db) {
        float frac = db <= -30f ? 0f : db >= 0f ? 1f : (30f + db) / 30f;
        volume = frac * frac;
        if (track != null) track.setVolume(volume);
    }

    synchronized void flush() {
        if (track != null) { track.pause(); track.flush(); track.play(); }
        if (aac != null) try { aac.flush(); } catch (Exception ignored) {}
    }

    synchronized void release() {
        if (aac != null) { try { aac.stop(); aac.release(); } catch (Exception ignored) {} aac = null; }
        if (track != null) { try { track.stop(); track.release(); } catch (Exception ignored) {} track = null; }
        ct = -1;
    }
}
