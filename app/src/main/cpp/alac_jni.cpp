// ALAC (Apple Lossless) decoder for AirPlay audio-only streams; Android has no ALAC MediaCodec.
#include <jni.h>
#include <stdint.h>
#include <string.h>
#include "ALACDecoder.h"
#include "ALACBitUtilities.h"

static ALACDecoder *decoder = nullptr;
static uint8_t pcm[4096 * 2 * 2];

extern "C" JNIEXPORT jboolean JNICALL
Java_app_lumacast_Native_alacInit(JNIEnv *, jclass) {
    // ALACSpecificConfig used by AirPlay: 352 frames, 16-bit, stereo, 44100 Hz
    static uint8_t cookie[24] = {
        0x00, 0x00, 0x01, 0x60, 0x00, 0x10, 0x28, 0x0a, 0x0e, 0x02, 0x00, 0xff,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xac, 0x44};
    delete decoder;
    decoder = new ALACDecoder();
    return decoder->Init(cookie, sizeof(cookie)) == 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_lumacast_Native_alacDecode(JNIEnv *env, jclass, jbyteArray in, jint len, jbyteArray out) {
    if (!decoder) return 0;
    jbyte *src = env->GetByteArrayElements(in, nullptr);
    BitBuffer bits;
    BitBufferInit(&bits, (uint8_t *) src, (uint32_t) len);
    uint32_t samples = 0;
    int32_t err = decoder->Decode(&bits, pcm, 352, 2, &samples);
    env->ReleaseByteArrayElements(in, src, JNI_ABORT);
    if (err != 0) return 0;
    jint bytes = (jint) (samples * 2 * 2);
    if (bytes > env->GetArrayLength(out)) bytes = env->GetArrayLength(out);
    env->SetByteArrayRegion(out, 0, bytes, (jbyte *) pcm);
    return bytes;
}
