/*
 * libollama-ml: CPU feature probing and one int8 dot product.
 *
 * Registered dynamically in JNI_OnLoad rather than by symbol name so the
 * exported surface stays at exactly one symbol. That also means a Kotlin-side
 * rename is a link-time failure here instead of an UnsatisfiedLinkError on a
 * user's device.
 *
 * See CMakeLists.txt: this file is not currently compiled by any Gradle build.
 */
#include <jni.h>
#include <stddef.h>
#include <string.h>

#if defined(__ANDROID__)
#include <sys/auxv.h>
#endif

#if defined(__aarch64__)
#include <arm_neon.h>
#endif

#define ML_CLASS "io/github/jaypetez/ollamamobile/ml/NativeCpuFeatures"

/*
 * getauxval is the kernel's own answer and the same source ggml's dispatch
 * reads. It is available in the NDK's libc from API 18 (AT_HWCAP) and API 21
 * (AT_HWCAP2); minSdk here is 29, so no dlsym dance is needed.
 *
 * On a non-Android host build (unit-testing this file on a workstation) the
 * header is absent and 0 is returned, which the Kotlin side reads as "no
 * optional features" — never as "detection succeeded and found nothing", because
 * the Kotlin side only calls this at all when the library loaded.
 */
static jlong ml_hwcap(unsigned long type) {
#if defined(__ANDROID__)
    return (jlong)getauxval(type);
#else
    (void)type;
    return 0;
#endif
}

static jlong ml_native_hwcap(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
#if defined(__ANDROID__)
    return ml_hwcap(AT_HWCAP);
#else
    return 0;
#endif
}

static jlong ml_native_hwcap2(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
#if defined(__ANDROID__)
    return ml_hwcap(AT_HWCAP2);
#else
    return 0;
#endif
}

/*
 * sum(a[i] * b[i]) over signed bytes.
 *
 * The scalar loop is the definition; the NEON path must agree with it exactly,
 * not approximately, because this is integer arithmetic — there is no rounding
 * to hide behind. `VectorKernelsTest` asserts that equality.
 *
 * Widening is int8 -> int16 -> int32. vdotq_s32 would be one instruction
 * instead of four, but it requires the dotprod extension, and this library is
 * built for the whole arm64-v8a ABI with no variant fan-out. A runtime branch
 * on AT_HWCAP would be the way to add it; it is not added speculatively,
 * because nothing here has been measured on ARM hardware.
 */
static jint ml_native_dot_int8(JNIEnv *env, jclass clazz, jbyteArray a, jbyteArray b, jint length) {
    (void)clazz;

    if (length <= 0) {
        return 0;
    }

    jbyte *pa = (*env)->GetPrimitiveArrayCritical(env, a, NULL);
    if (pa == NULL) {
        return 0;
    }
    jbyte *pb = (*env)->GetPrimitiveArrayCritical(env, b, NULL);
    if (pb == NULL) {
        (*env)->ReleasePrimitiveArrayCritical(env, a, pa, JNI_ABORT);
        return 0;
    }

    const signed char *x = (const signed char *)pa;
    const signed char *y = (const signed char *)pb;
    int i = 0;
    int sum = 0;

#if defined(__aarch64__)
    int32x4_t acc = vdupq_n_s32(0);
    for (; i + 16 <= length; i += 16) {
        const int8x16_t vx = vld1q_s8(x + i);
        const int8x16_t vy = vld1q_s8(y + i);

        const int16x8_t lo = vmull_s8(vget_low_s8(vx), vget_low_s8(vy));
        const int16x8_t hi = vmull_s8(vget_high_s8(vx), vget_high_s8(vy));

        acc = vaddq_s32(acc, vaddl_s16(vget_low_s16(lo), vget_high_s16(lo)));
        acc = vaddq_s32(acc, vaddl_s16(vget_low_s16(hi), vget_high_s16(hi)));
    }
    sum += vaddvq_s32(acc);
#endif

    for (; i < length; ++i) {
        sum += (int)x[i] * (int)y[i];
    }

    (*env)->ReleasePrimitiveArrayCritical(env, b, pb, JNI_ABORT);
    (*env)->ReleasePrimitiveArrayCritical(env, a, pa, JNI_ABORT);
    return (jint)sum;
}

static const JNINativeMethod kMethods[] = {
    {"nativeHwcap", "()J", (void *)ml_native_hwcap},
    {"nativeHwcap2", "()J", (void *)ml_native_hwcap2},
    {"nativeDotInt8", "([B[BI)I", (void *)ml_native_dot_int8},
};

__attribute__((visibility("default"))) jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;

    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    jclass clazz = (*env)->FindClass(env, ML_CLASS);
    if (clazz == NULL) {
        return JNI_ERR;
    }

    const jint count = (jint)(sizeof(kMethods) / sizeof(kMethods[0]));
    if ((*env)->RegisterNatives(env, clazz, kMethods, count) != JNI_OK) {
        return JNI_ERR;
    }

    (*env)->DeleteLocalRef(env, clazz);
    return JNI_VERSION_1_6;
}
