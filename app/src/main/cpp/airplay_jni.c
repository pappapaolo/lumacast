/* JNI glue between UxPlay's AirPlay receiver core (lib/) and the Android app. */
#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <stdbool.h>

#include "raop.h"
#include "dnssd.h"
#include "stream.h"
#include "logger.h"

#define TAG "AirPlayTV"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static JavaVM *jvm;
static jclass cls;
static jmethodID m_video, m_audio, m_audio_format, m_volume, m_conn, m_video_size,
                 m_video_stop, m_flush;
static pthread_key_t env_key;

static raop_t *raop;
static dnssd_t *dnssd;
static int open_connections;
static double current_volume = 0.0;

static void detach_thread(void *unused) { (*jvm)->DetachCurrentThread(jvm); }

static JNIEnv *env_get(void) {
    JNIEnv *env = NULL;
    if ((*jvm)->GetEnv(jvm, (void **) &env, JNI_VERSION_1_6) == JNI_OK) return env;
    if ((*jvm)->AttachCurrentThread(jvm, &env, NULL) != JNI_OK) return NULL;
    pthread_setspecific(env_key, env);   /* detach automatically when the thread exits */
    return env;
}

static void clear_exception(JNIEnv *env) {
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
}

static jbyteArray bytes(JNIEnv *env, const void *data, int len) {
    jbyteArray a = (*env)->NewByteArray(env, len);
    if (a) (*env)->SetByteArrayRegion(env, a, 0, len, (const jbyte *) data);
    return a;
}

/* ---- raop callbacks ---- */
static void cb_video_process(void *c, raop_ntp_t *ntp, video_decode_struct *data) {
    JNIEnv *env = env_get();
    if (!env || data->data_len <= 0) return;
    jbyteArray a = bytes(env, data->data, data->data_len);
    (*env)->CallStaticVoidMethod(env, cls, m_video, a, (jboolean) data->is_h265);
    clear_exception(env);
    (*env)->DeleteLocalRef(env, a);
}

static void cb_audio_process(void *c, raop_ntp_t *ntp, audio_decode_struct *data) {
    JNIEnv *env = env_get();
    if (!env || data->data_len <= 0) return;
    jbyteArray a = bytes(env, data->data, data->data_len);
    (*env)->CallStaticVoidMethod(env, cls, m_audio, a, (jint) data->ct);
    clear_exception(env);
    (*env)->DeleteLocalRef(env, a);
}

static void cb_audio_get_format(void *c, unsigned char *ct, unsigned short *spf, bool *usingScreen,
                                bool *isMedia, uint64_t *audioFormat) {
    JNIEnv *env = env_get();
    if (!env) return;
    LOGI("audio format ct=%d spf=%d screen=%d", *ct, *spf, *usingScreen);
    (*env)->CallStaticVoidMethod(env, cls, m_audio_format, (jint) *ct, (jint) *spf);
    clear_exception(env);
}

static void cb_audio_set_volume(void *c, float volume) {
    current_volume = volume;
    JNIEnv *env = env_get();
    if (!env) return;
    (*env)->CallStaticVoidMethod(env, cls, m_volume, (jfloat) volume);
    clear_exception(env);
}

static double cb_audio_set_client_volume(void *c) { return current_volume; }

static void notify_conn(void) {
    JNIEnv *env = env_get();
    if (!env) return;
    (*env)->CallStaticVoidMethod(env, cls, m_conn, (jint) open_connections);
    clear_exception(env);
}
static void cb_conn_init(void *c) { open_connections++; LOGI("connections: %d", open_connections); notify_conn(); }
static void cb_conn_destroy(void *c) {
    if (open_connections > 0) open_connections--;
    LOGI("connections: %d", open_connections);
    notify_conn();
}

static void video_stop(void) {
    JNIEnv *env = env_get();
    if (!env) return;
    (*env)->CallStaticVoidMethod(env, cls, m_video_stop);
    clear_exception(env);
}
static void cb_conn_reset(void *c, int reason) { LOGI("conn_reset %d", reason); video_stop(); }
static void cb_video_reset(void *c, reset_type_t t) { LOGI("video_reset %d", (int) t); video_stop(); }

static int cb_video_set_codec_and_size(void *c, video_codec_t codec, unsigned short ws, unsigned short hs,
                                       unsigned short x, unsigned short y, unsigned short w, unsigned short h) {
    JNIEnv *env = env_get();
    if (!env) return -1;
    LOGI("video codec=%d source=%dx%d content=%dx%d", (int) codec, ws, hs, w, h);
    (*env)->CallStaticVoidMethod(env, cls, m_video_size, (jint) ws, (jint) hs,
                                 (jboolean) (codec == VIDEO_CODEC_H265));
    clear_exception(env);
    return 0;
}

static void flush(int what) {
    JNIEnv *env = env_get();
    if (!env) return;
    (*env)->CallStaticVoidMethod(env, cls, m_flush, (jint) what);
    clear_exception(env);
}
static void cb_audio_flush(void *c) { flush(0); }
static void cb_video_flush(void *c) { flush(1); }

static void cb_report_client_request(void *c, char *deviceid, char *model, char *name, bool *admit) {
    LOGI("client: %s (%s) %s", name ? name : "?", model ? model : "?", deviceid ? deviceid : "?");
    *admit = true;
}

/* unused features: keep non-NULL so the core never calls through a NULL pointer */
static void cb_noop(void *c) {}
static void cb_conn_feedback(void *c) {}
static void cb_audio_set_metadata(void *c, const void *b, int l) {}
static void cb_audio_set_coverart(void *c, const void *b, int l) {}
static void cb_audio_remote_control_id(void *c, const char *a, const char *b) {}
static void cb_audio_set_progress(void *c, uint32_t *s, uint32_t *cur, uint32_t *e) {}
static void cb_mirror_video_running(void *c, bool r) {}
static void cb_display_pin(void *c, char *pin) {}
static void cb_register_client(void *c, const char *d, const char *pk, const char *n) {}
static bool cb_check_register(void *c, const char *pk) { return true; }
static const char *cb_passwd(void *c, int *len) { *len = 0; return NULL; }
static void cb_export_dacp(void *c, const char *a, const char *d) {}
static void cb_on_video_play(void *c, const char *loc, const float pos) {}
static void cb_on_video_scrub(void *c, const float pos) {}
static void cb_on_video_rate(void *c, const float rate) {}
static void cb_on_video_acquire_playback_info(void *c, playback_info_t *p) {}
static float cb_on_video_playlist_remove(void *c) { return 0.0f; }
static void cb_get_custom_profile(void *c, const char **p) { *p = NULL; }

static void log_callback(void *c, int level, const char *msg) {
    int prio = level <= LOGGER_ERR ? ANDROID_LOG_ERROR : level <= LOGGER_INFO ? ANDROID_LOG_INFO : ANDROID_LOG_DEBUG;
    __android_log_write(prio, "UxPlay", msg);
}

static void set_features(dnssd_t *d) {
    static const int on[] = {1, 2, 5, 6, 7, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 25, 28, 30};
    for (int i = 0; i < 32; i++) dnssd_set_airplay_features(d, i, 0);
    for (unsigned i = 0; i < sizeof(on) / sizeof(on[0]); i++) dnssd_set_airplay_features(d, on[i], 1);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    jvm = vm;
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) return -1;
    pthread_key_create(&env_key, detach_thread);
    jclass local = (*env)->FindClass(env, "app/lumacast/Native");
    cls = (*env)->NewGlobalRef(env, local);
    m_video = (*env)->GetStaticMethodID(env, cls, "onVideo", "([BZ)V");
    m_audio = (*env)->GetStaticMethodID(env, cls, "onAudio", "([BI)V");
    m_audio_format = (*env)->GetStaticMethodID(env, cls, "onAudioFormat", "(II)V");
    m_volume = (*env)->GetStaticMethodID(env, cls, "onVolume", "(F)V");
    m_conn = (*env)->GetStaticMethodID(env, cls, "onConnections", "(I)V");
    m_video_size = (*env)->GetStaticMethodID(env, cls, "onVideoSize", "(IIZ)V");
    m_video_stop = (*env)->GetStaticMethodID(env, cls, "onVideoStop", "()V");
    m_flush = (*env)->GetStaticMethodID(env, cls, "onFlush", "(I)V");
    return JNI_VERSION_1_6;
}

/* name: shown in the AirPlay menu; hw: 6-byte MAC-like device id; keyfile: persists the server key
 * (so paired clients stay paired); w/h/fps: display mode advertised to the sender. */
JNIEXPORT jint JNICALL
Java_app_lumacast_Native_start(JNIEnv *env, jclass c, jstring jname, jbyteArray jhw, jstring jkeyfile,
                                      jint width, jint height, jint fps) {
    if (raop) return raop_get_port(raop);
    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    const char *keyfile = (*env)->GetStringUTFChars(env, jkeyfile, NULL);
    jbyte hw[6];
    (*env)->GetByteArrayRegion(env, jhw, 0, 6, hw);
    char mac[18];
    snprintf(mac, sizeof(mac), "%02x:%02x:%02x:%02x:%02x:%02x",
             (unsigned char) hw[0], (unsigned char) hw[1], (unsigned char) hw[2],
             (unsigned char) hw[3], (unsigned char) hw[4], (unsigned char) hw[5]);
    int ret = -1, err = 0;

    dnssd = dnssd_init(name, strlen(name), (const char *) hw, 6, 0, &err);
    if (err || !dnssd) { LOGE("dnssd_init failed %d", err); goto out; }
    set_features(dnssd);

    raop_callbacks_t cbs;
    memset(&cbs, 0, sizeof(cbs));
    cbs.audio_process = cb_audio_process;
    cbs.video_process = cb_video_process;
    cbs.video_pause = cb_noop;
    cbs.video_resume = cb_noop;
    cbs.conn_feedback = cb_conn_feedback;
    cbs.conn_reset = cb_conn_reset;
    cbs.video_reset = cb_video_reset;
    cbs.video_set_codec_and_size = cb_video_set_codec_and_size;
    cbs.conn_init = cb_conn_init;
    cbs.conn_destroy = cb_conn_destroy;
    cbs.audio_flush = cb_audio_flush;
    cbs.video_flush = cb_video_flush;
    cbs.audio_set_client_volume = cb_audio_set_client_volume;
    cbs.audio_set_volume = cb_audio_set_volume;
    cbs.audio_set_metadata = cb_audio_set_metadata;
    cbs.audio_set_coverart = cb_audio_set_coverart;
    cbs.audio_stop_coverart_rendering = cb_noop;
    cbs.audio_remote_control_id = cb_audio_remote_control_id;
    cbs.audio_set_progress = cb_audio_set_progress;
    cbs.audio_get_format = cb_audio_get_format;
    cbs.mirror_video_running = cb_mirror_video_running;
    cbs.report_client_request = cb_report_client_request;
    cbs.display_pin = cb_display_pin;
    cbs.register_client = cb_register_client;
    cbs.check_register = cb_check_register;
    cbs.passwd = cb_passwd;
    cbs.export_dacp = cb_export_dacp;
    cbs.on_video_play = cb_on_video_play;
    cbs.on_video_scrub = cb_on_video_scrub;
    cbs.on_video_rate = cb_on_video_rate;
    cbs.on_video_stop = cb_noop;
    cbs.on_video_acquire_playback_info = cb_on_video_acquire_playback_info;
    cbs.on_video_playlist_remove = cb_on_video_playlist_remove;
    cbs.get_custom_profile = cb_get_custom_profile;

    raop = raop_init(&cbs);
    if (!raop) { LOGE("raop_init failed"); goto out; }
    raop_set_log_callback(raop, log_callback, NULL);
    raop_set_log_level(raop, LOGGER_INFO);
    if (raop_init2(raop, 1, mac, keyfile)) { LOGE("raop_init2 failed"); raop_destroy(raop); raop = NULL; goto out; }

    raop_set_plist(raop, "width", width);
    raop_set_plist(raop, "height", height);
    raop_set_plist(raop, "refreshRate", fps);
    raop_set_plist(raop, "maxFPS", fps);

    unsigned short tcp[2] = {0, 0}, udp[3] = {0, 0, 0};
    raop_set_tcp_ports(raop, tcp);
    raop_set_udp_ports(raop, udp);
    unsigned short port = raop_get_port(raop);
    raop_start_httpd(raop, &port);
    raop_set_port(raop, port);
    raop_set_dnssd(raop, dnssd);

    if (dnssd_register_raop(dnssd, port)) LOGE("register raop failed");
    if (dnssd_register_airplay(dnssd, port)) LOGE("register airplay failed");
    LOGI("AirPlay receiver '%s' (%s) listening on port %d", name, mac, port);
    ret = port;
out:
    (*env)->ReleaseStringUTFChars(env, jname, name);
    (*env)->ReleaseStringUTFChars(env, jkeyfile, keyfile);
    return ret;
}

JNIEXPORT void JNICALL
Java_app_lumacast_Native_stop(JNIEnv *env, jclass c) {
    if (raop) { raop_destroy(raop); raop = NULL; }
    if (dnssd) {
        dnssd_unregister_raop(dnssd);
        dnssd_unregister_airplay(dnssd);
        dnssd_destroy(dnssd);
        dnssd = NULL;
    }
    open_connections = 0;
}
