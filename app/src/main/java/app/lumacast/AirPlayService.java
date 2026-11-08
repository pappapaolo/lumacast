package app.lumacast;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.security.SecureRandom;

/** Always-on AirPlay receiver: advertises itself, receives streams, opens the mirror screen on demand. */
public class AirPlayService extends Service implements Native.Listener {
    private static final String TAG = "AirPlayTV";
    static final VideoSink video = new VideoSink();
    static final AudioSink audio = new AudioSink();
    static volatile String status = "Starting…";
    static volatile String deviceName = "Projector";

    private final Handler main = new Handler(Looper.getMainLooper());
    private WifiManager.WifiLock wifiLock;
    private WifiManager.MulticastLock multicastLock;
    private volatile boolean mirrorDismissed;
    private volatile long lastLaunch;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("airplay", getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, "airplay")
                .setContentTitle(getString(R.string.app_name) + " is ready")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        // low-latency Wi-Fi only while a sender is connected: holding it all the time
        // starves the Bluetooth remote on this shared Wi-Fi/BT chip (laggy, bunched key presses)
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "airplay");
        wifiLock.setReferenceCounted(false);
        multicastLock = wm.createMulticastLock("airplay-mdns");
        multicastLock.setReferenceCounted(false);
        multicastLock.acquire();

        Native.listener = this;
        new Thread(this::startReceiver, "airplay-start").start();
    }

    private void startReceiver() {
        SharedPreferences p = getSharedPreferences("airplay", MODE_PRIVATE);
        String hex = p.getString("hw", null);
        byte[] hw = new byte[6];
        if (hex == null) {
            new SecureRandom().nextBytes(hw);
            hw[0] = (byte) ((hw[0] & 0xfe) | 0x02); // locally administered unicast
            StringBuilder sb = new StringBuilder();
            for (byte b : hw) sb.append(String.format("%02x", b));
            p.edit().putString("hw", sb.toString()).apply();
        } else {
            for (int i = 0; i < 6; i++) hw[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        String tvName = android.provider.Settings.Global.getString(getContentResolver(), android.provider.Settings.Global.DEVICE_NAME);
        if (tvName == null || tvName.trim().isEmpty()) tvName = android.os.Build.MODEL;
        deviceName = p.getString("name", tvName.trim());
        int port = Native.start(deviceName, hw, getFilesDir() + "/airplay.pem", 1920, 1080, 30);
        status = port > 0 ? "Ready" : "Failed to start (see logcat)";
        Log.i(TAG, "receiver start: port " + port);
    }

    static void rename(android.content.Context ctx, String name) {
        ctx.getSharedPreferences("airplay", MODE_PRIVATE).edit().putString("name", name).apply();
        deviceName = name;
        new Thread(() -> {
            Native.stop();
            if (instance != null) instance.startReceiver();
        }, "airplay-restart").start();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

    @Override public void onDestroy() {
        Native.listener = null;
        Native.stop();
        video.stop();
        audio.release();
        if (wifiLock.isHeld()) wifiLock.release();
        if (multicastLock.isHeld()) multicastLock.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    static void dismissedByUser(Context ctx) {
        // back button on the mirror screen: stay hidden until the next mirroring session
        if (instance != null) instance.mirrorDismissed = true;
    }
    private static AirPlayService instance;
    { instance = this; }

    private void launchMirror() {
        if (mirrorDismissed || MirrorActivity.current != null) return;
        long now = System.currentTimeMillis();
        if (now - lastLaunch < 3000) return;
        lastLaunch = now;
        main.post(() -> {
            Intent i = new Intent(this, MirrorActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            try { startActivity(i); } catch (Exception e) { Log.e(TAG, "cannot open mirror screen", e); }
        });
    }

    private void closeMirror() {
        main.post(() -> { if (MirrorActivity.current != null) MirrorActivity.current.finish(); });
    }

    // ---- Native.Listener ----
    @Override public void onVideoSize(int w, int h, boolean h265) {
        mirrorDismissed = false;
        video.setFormat(w, h, h265);
        launchMirror();
    }
    @Override public void onVideo(byte[] d, boolean h265) { video.onFrame(d, h265); launchMirror(); }
    @Override public void onVideoStop() { video.stop(); closeMirror(); }
    @Override public void onAudio(byte[] d, int ct) { audio.onData(d, ct); }
    @Override public void onAudioFormat(int ct, int spf) { audio.setFormat(ct); }
    @Override public void onVolume(float db) { audio.setVolume(db); }
    @Override public void onFlush(int what) { if (what == 0) audio.flush(); }
    @Override public void onConnections(int count) {
        status = count > 0 ? "Connected" : "Ready";
        if (count > 0) wifiLock.acquire(); else if (wifiLock.isHeld()) wifiLock.release();
        if (count == 0) { audio.release(); video.stop(); closeMirror(); }
    }
}
