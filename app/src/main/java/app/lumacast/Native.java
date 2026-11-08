package app.lumacast;

/** Bridge to the native AirPlay receiver (UxPlay core). Callbacks arrive on native network threads. */
public final class Native {
    static { System.loadLibrary("airplay"); }

    public static native int start(String name, byte[] hwAddr, String keyfile, int width, int height, int fps);
    public static native void stop();
    public static native boolean alacInit();
    public static native int alacDecode(byte[] in, int len, byte[] out);

    public interface Listener {
        void onVideo(byte[] data, boolean h265);
        void onAudio(byte[] data, int ct);
        void onAudioFormat(int ct, int spf);
        void onVolume(float db);
        void onConnections(int count);
        void onVideoSize(int width, int height, boolean h265);
        void onVideoStop();
        void onFlush(int what);
    }

    static volatile Listener listener;

    static void onVideo(byte[] d, boolean h265) { Listener l = listener; if (l != null) l.onVideo(d, h265); }
    static void onAudio(byte[] d, int ct) { Listener l = listener; if (l != null) l.onAudio(d, ct); }
    static void onAudioFormat(int ct, int spf) { Listener l = listener; if (l != null) l.onAudioFormat(ct, spf); }
    static void onVolume(float db) { Listener l = listener; if (l != null) l.onVolume(db); }
    static void onConnections(int n) { Listener l = listener; if (l != null) l.onConnections(n); }
    static void onVideoSize(int w, int h, boolean h265) { Listener l = listener; if (l != null) l.onVideoSize(w, h, h265); }
    static void onVideoStop() { Listener l = listener; if (l != null) l.onVideoStop(); }
    static void onFlush(int what) { Listener l = listener; if (l != null) l.onFlush(what); }

    private Native() {}
}
