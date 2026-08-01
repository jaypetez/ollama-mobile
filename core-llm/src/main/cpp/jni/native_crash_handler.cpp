// Async-signal-safe native crash recorder.
//
// ## What this is for
//
// CrashSentinel (Kotlin) proves that a previous run entered native code and
// never came out. That is enough to escalate a backend fallback, and it is all
// it can do: it is armed *before* the call and cleared *after*, so it knows
// nothing about the crash itself. This file adds the other half -- which signal
// fired, at which address, on which thread -- so a bug report distinguishes
// "SIGILL in a CPU kernel the device lied about supporting" from "SIGSEGV
// because the model file was truncated". Those have different fixes and the
// sentinel cannot tell them apart.
//
// ## Why it looks like this
//
// A signal handler may only call functions on the POSIX async-signal-safe list.
// The process is, by definition, already in an undefined state: the allocator
// lock may be held by the thread we interrupted, so any malloc deadlocks
// instead of returning; the JNI environment may be mid-GC, so any JNI call is
// undefined; libunwind allocates, so backtracing is out. A handler that
// violates this does not fail loudly -- it hangs, and the user sees an ANR
// instead of a crash dialog, with no record written either way.
//
// Everything the record needs is therefore staged into static storage while the
// process is still healthy, and the handler itself does nothing but fill in a
// few integers with hand-rolled formatting and call write(2). No malloc, no
// snprintf (not on the list), no unwinding, no JNI, no C++ runtime.
//
// After writing, the handler restores the default disposition and re-raises, so
// the kernel produces the tombstone and the platform's own crash path runs
// exactly as it would have. Swallowing the signal would leave a process with a
// dead thread and no dialog.

#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <string.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#include <jni.h>

namespace {

constexpr int kSignals[] = {SIGSEGV, SIGABRT, SIGILL, SIGBUS, SIGFPE};
constexpr size_t kSignalCount = sizeof(kSignals) / sizeof(kSignals[0]);

// Bounded because it is written from a signal handler: a fixed array in BSS
// needs no allocation and cannot be reallocated underneath us.
constexpr size_t kPathMax = 512;
constexpr size_t kPhaseMax = 64;
constexpr size_t kRecordMax = 1024;

// The alternate stack must exist before a stack-overflow SIGSEGV, because by
// then there is no room on the faulting stack to run a handler at all. SIGSTKSZ
// is not a compile-time constant on modern bionic, so a generous fixed size is
// used instead.
constexpr size_t kAltStackSize = 64 * 1024;

// ---------------------------------------------------------------------------
// Pre-staged state. Everything the handler reads is written here at install
// time, while malloc and the JNI environment are still usable.
// ---------------------------------------------------------------------------

char g_record_path[kPathMax];
char g_phase[kPhaseMax];
char g_scratch[kRecordMax];
char g_alt_stack[kAltStackSize];

struct sigaction g_previous[kSignalCount];
volatile sig_atomic_t g_installed = 0;

// Guards against a second signal arriving while the first is being written --
// re-entering would interleave two records into one unparseable line.
volatile sig_atomic_t g_handling = 0;

// ---------------------------------------------------------------------------
// Hand-rolled formatting. snprintf is NOT async-signal-safe.
// ---------------------------------------------------------------------------

// Appends a NUL-terminated string. Returns the new offset.
size_t AppendString(char* out, size_t offset, size_t capacity, const char* text) {
    while (*text != '\0' && offset + 1 < capacity) {
        out[offset++] = *text++;
    }
    return offset;
}

// Appends an unsigned value in the given base. Digits are produced backwards
// into a small stack buffer, which is fine: this is a leaf call with no
// allocation and a bounded, tiny frame.
size_t AppendUnsigned(char* out, size_t offset, size_t capacity, uint64_t value, unsigned base) {
    char digits[24];
    size_t count = 0;
    do {
        const unsigned digit = static_cast<unsigned>(value % base);
        digits[count++] = static_cast<char>(digit < 10 ? ('0' + digit) : ('a' + digit - 10));
        value /= base;
    } while (value != 0 && count < sizeof(digits));

    while (count > 0 && offset + 1 < capacity) {
        out[offset++] = digits[--count];
    }
    return offset;
}

size_t AppendSigned(char* out, size_t offset, size_t capacity, int64_t value) {
    if (value < 0) {
        if (offset + 1 < capacity) {
            out[offset++] = '-';
        }
        return AppendUnsigned(out, offset, capacity, static_cast<uint64_t>(-value), 10);
    }
    return AppendUnsigned(out, offset, capacity, static_cast<uint64_t>(value), 10);
}

const char* SignalName(int signal_number) {
    switch (signal_number) {
        case SIGSEGV: return "SIGSEGV";
        case SIGABRT: return "SIGABRT";
        case SIGILL: return "SIGILL";
        case SIGBUS: return "SIGBUS";
        case SIGFPE: return "SIGFPE";
        default: return "SIGNAL";
    }
}

// write(2) is async-signal-safe but may write fewer bytes than asked, and may
// fail with EINTR. Neither is worth losing the record over.
void WriteAll(int fd, const char* data, size_t length) {
    size_t written = 0;
    while (written < length) {
        const ssize_t result = write(fd, data + written, length - written);
        if (result > 0) {
            written += static_cast<size_t>(result);
        } else if (result < 0 && errno == EINTR) {
            continue;
        } else {
            return;
        }
    }
}

// ---------------------------------------------------------------------------
// The handler
// ---------------------------------------------------------------------------

void HandleSignal(int signal_number, siginfo_t* info, void* context) {
    (void)context;

    if (g_handling == 0) {
        g_handling = 1;

        // O_TRUNC, not O_APPEND: the newest crash is the one that matters and a
        // fixed-size single record cannot grow the file without bound during a
        // crash loop. open/write/close are all on the async-signal-safe list.
        const int fd = open(g_record_path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
        if (fd >= 0) {
            size_t offset = 0;
            offset = AppendString(g_scratch, offset, kRecordMax, "v1 signal=");
            offset = AppendString(g_scratch, offset, kRecordMax, SignalName(signal_number));
            offset = AppendString(g_scratch, offset, kRecordMax, " signo=");
            offset = AppendSigned(g_scratch, offset, kRecordMax, signal_number);

            offset = AppendString(g_scratch, offset, kRecordMax, " code=");
            offset = AppendSigned(g_scratch, offset, kRecordMax, info != nullptr ? info->si_code : 0);

            offset = AppendString(g_scratch, offset, kRecordMax, " fault=0x");
            const uintptr_t fault =
                info != nullptr ? reinterpret_cast<uintptr_t>(info->si_addr) : 0;
            offset = AppendUnsigned(g_scratch, offset, kRecordMax, fault, 16);

            offset = AppendString(g_scratch, offset, kRecordMax, " pid=");
            offset = AppendSigned(g_scratch, offset, kRecordMax, getpid());
            offset = AppendString(g_scratch, offset, kRecordMax, " tid=");
            offset = AppendSigned(g_scratch, offset, kRecordMax, gettid());

            // clock_gettime is async-signal-safe; localtime and strftime are
            // not, so the record carries raw epoch seconds and Kotlin formats
            // it on the next launch.
            struct timespec now;
            now.tv_sec = 0;
            now.tv_nsec = 0;
            clock_gettime(CLOCK_REALTIME, &now);
            offset = AppendString(g_scratch, offset, kRecordMax, " epoch=");
            offset = AppendSigned(g_scratch, offset, kRecordMax, static_cast<int64_t>(now.tv_sec));

            offset = AppendString(g_scratch, offset, kRecordMax, " phase=");
            offset = AppendString(g_scratch, offset, kRecordMax, g_phase);
            offset = AppendString(g_scratch, offset, kRecordMax, "\n");

            WriteAll(fd, g_scratch, offset);
            // fsync is not on the safe list; close is, and the kernel keeps the
            // page cache across process death. Only a kernel panic loses this.
            close(fd);
        }
    }

    // Restore the previous disposition and re-raise so the platform's tombstone
    // machinery runs. Falling off the end of the handler would simply re-enter
    // it forever for a fault-type signal, because the faulting instruction is
    // retried on return.
    for (size_t index = 0; index < kSignalCount; ++index) {
        if (kSignals[index] == signal_number) {
            sigaction(signal_number, &g_previous[index], nullptr);
            break;
        }
    }
    raise(signal_number);
}

}  // namespace

// ---------------------------------------------------------------------------
// JNI surface
//
// Discovered by name rather than RegisterNatives so this file stays independent
// of llama_jni.cpp. JNIEXPORT carries default visibility, which is what makes
// it findable under -fvisibility=hidden.
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_jaypetez_ollamamobile_llm_internal_NativeCrashHandler_nativeInstall(
    JNIEnv* env, jobject /*thiz*/, jstring record_path) {
    if (g_installed != 0) {
        return JNI_TRUE;
    }
    if (record_path == nullptr) {
        return JNI_FALSE;
    }

    const char* path = env->GetStringUTFChars(record_path, nullptr);
    if (path == nullptr) {
        return JNI_FALSE;
    }
    const size_t length = strlen(path);
    if (length == 0 || length >= kPathMax) {
        env->ReleaseStringUTFChars(record_path, path);
        return JNI_FALSE;
    }
    memcpy(g_record_path, path, length + 1);
    env->ReleaseStringUTFChars(record_path, path);

    memcpy(g_phase, "unset", sizeof("unset"));

    stack_t alt_stack;
    memset(&alt_stack, 0, sizeof(alt_stack));
    alt_stack.ss_sp = g_alt_stack;
    alt_stack.ss_size = kAltStackSize;
    alt_stack.ss_flags = 0;
    sigaltstack(&alt_stack, nullptr);

    struct sigaction action;
    memset(&action, 0, sizeof(action));
    action.sa_sigaction = HandleSignal;
    // SA_ONSTACK is the point of the alternate stack above. SA_SIGINFO is what
    // supplies si_code and si_addr, which is most of the diagnostic value.
    action.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&action.sa_mask);

    for (size_t index = 0; index < kSignalCount; ++index) {
        sigaction(kSignals[index], &action, &g_previous[index]);
    }

    g_installed = 1;
    __android_log_print(ANDROID_LOG_INFO, "ollamamobile", "native crash handler installed");
    return JNI_TRUE;
}

/**
 * Stages a short label describing what native code is about to do, so a record
 * written later says "model-load" or "decode" instead of nothing.
 *
 * Copied into static storage here, on a healthy thread, precisely so that the
 * handler never has to touch a jstring.
 */
extern "C" JNIEXPORT void JNICALL
Java_io_github_jaypetez_ollamamobile_llm_internal_NativeCrashHandler_nativeSetPhase(
    JNIEnv* env, jobject /*thiz*/, jstring phase) {
    if (phase == nullptr) {
        return;
    }
    const char* text = env->GetStringUTFChars(phase, nullptr);
    if (text == nullptr) {
        return;
    }
    size_t length = strlen(text);
    if (length >= kPhaseMax) {
        length = kPhaseMax - 1;
    }
    memcpy(g_phase, text, length);
    g_phase[length] = '\0';
    env->ReleaseStringUTFChars(phase, text);
}
