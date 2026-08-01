// The entire JNI surface of :core-llm, in one translation unit.
//
// Written against third_party/llama.cpp at tag b10150. Every llama_* spelling
// below was read out of include/llama.h at that commit rather than remembered:
// the C API is unversioned and renames without deprecation (llama_kv_self_* ->
// llama_memory_*, llama_load_model_from_file -> llama_model_load_from_file,
// context_params.use_mmap -> model_params.load_mode). If you bump the submodule
// pin, re-read the header before touching anything here.
//
// FOUR DESIGN RULES, each of which has a specific failure mode behind it. They
// are documented at length in docs/architecture/jni-boundary.md.
//
// 1. Handles are jlong. Native state hangs off an opaque integer, never off
//    fields of a Kotlin object reached with GetFieldID -- a rename or an R8
//    shrink would turn that into a NoSuchFieldError at runtime instead of a
//    compile error. The integer here is a registry key rather than a pointer,
//    so a stale handle is a lookup miss instead of a wild dereference.
//
// 2. Binding is RegisterNatives in JNI_OnLoad, not name mangling. R8 full mode
//    is on for release builds; a mangled Java_..._nativeFoo symbol embeds the
//    package and class name that R8 rewrites, so implicit binding breaks in
//    release only, after shrinking, usually on a user's device. One FindClass
//    string fails loudly at library load instead.
//
// 3. No file-static model/context globals. A chat model and an embedding model
//    have to be resident simultaneously for RAG; one global slot cannot hold
//    two of anything, and the corruption shows up as the chat model producing
//    garbage, which is the last place anyone looks. The only process-wide state
//    is the handle registry and a global error string.
//
// 4. Tokens are PULLED. See the comment above NativeGenerateNextToken.

#include <jni.h>

#include <android/log.h>

#include <chat.h>
#include <ggml-backend.h>
#include <llama.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <new>
#include <string>
#include <unordered_map>
#include <vector>

namespace {

constexpr const char* kTag = "OllamaMobileJNI";

// The one string that couples C++ to Kotlin. If it goes stale the failure is
// System.loadLibrary throwing at load time, which is loud, immediate and
// nowhere near a user's first inference.
constexpr const char* kBridgeClass = "io/github/jaypetez/ollamamobile/llm/internal/LlamaBridge";

// Mirrors NativeFinishReason.kt. Keep the two in sync; they are two halves of
// one enum that no compiler checks.
constexpr jint kFinishRunning = 0;
constexpr jint kFinishStop = 1;
constexpr jint kFinishLength = 2;
constexpr jint kFinishCancelled = 3;
constexpr jint kFinishError = 4;

// -------------------------------------------------------------------------
// Process-wide error channel
// -------------------------------------------------------------------------
//
// Session creation has no handle to hang an error off yet, so the message for a
// failed load lands here. This is a string, not state an inference depends on;
// see rule 3 above for why nothing else is global.
std::mutex g_error_mutex;
std::string g_last_error;

void SetGlobalError(std::string message) {
    std::lock_guard<std::mutex> guard(g_error_mutex);
    g_last_error = std::move(message);
    __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", g_last_error.c_str());
}

std::string CurrentGlobalError() {
    std::lock_guard<std::mutex> guard(g_error_mutex);
    return g_last_error;
}

void ClearGlobalError() {
    std::lock_guard<std::mutex> guard(g_error_mutex);
    g_last_error.clear();
}

void LlamaLogCallback(ggml_log_level level, const char* text, void* /*user_data*/) {
    int priority = ANDROID_LOG_DEBUG;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR:
            priority = ANDROID_LOG_ERROR;
            break;
        case GGML_LOG_LEVEL_WARN:
            priority = ANDROID_LOG_WARN;
            break;
        case GGML_LOG_LEVEL_INFO:
            priority = ANDROID_LOG_INFO;
            break;
        default:
            priority = ANDROID_LOG_DEBUG;
            break;
    }
    __android_log_print(priority, kTag, "%s", text != nullptr ? text : "");
}

// -------------------------------------------------------------------------
// Session
// -------------------------------------------------------------------------

struct Session {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    const llama_vocab* vocab = nullptr;
    llama_sampler* sampler = nullptr;
    llama_batch batch{};
    bool batch_allocated = false;
    common_chat_templates_ptr templates;

    // Read by ggml from inside llama_decode, on ggml's own worker threads.
    // Layer two of cancellation; see NativeRequestAbort.
    std::atomic<bool> abort{false};

    // Serialises every call that touches ctx. One session is driven by exactly
    // one engine thread in normal operation, but nothing in JNI enforces that
    // and a use-after-free here is a native crash rather than an exception.
    std::mutex lock;

    int n_ctx = 0;
    int n_batch = 0;
    bool embedding_mode = false;

    // Generation state.
    std::vector<llama_token> pending;  // queued for the next llama_decode
    std::string utf8_carry;            // bytes of a not-yet-complete code point
    int n_past = 0;
    int max_tokens = 0;
    int generated = 0;
    int prompt_tokens = 0;
    bool generating = false;
    jint finish_reason = kFinishRunning;
    std::string last_error;
    int64_t prefill_us = 0;
    int64_t eval_us = 0;
    int64_t load_us = 0;

    ~Session() {
        // Order matters: the sampler and the batch belong to the context's
        // lifetime, and the context to the model's.
        if (sampler != nullptr) {
            llama_sampler_free(sampler);
        }
        if (batch_allocated) {
            llama_batch_free(batch);
        }
        templates.reset();
        if (ctx != nullptr) {
            llama_free(ctx);
        }
        if (model != nullptr) {
            llama_model_free(model);
        }
    }
};

using SessionPtr = std::shared_ptr<Session>;

std::mutex g_registry_mutex;
std::unordered_map<jlong, SessionPtr> g_sessions;
jlong g_next_handle = 1;

// Returns a *counted* reference on purpose. NativeRequestAbort can be called
// from any thread at any time, including while the engine thread is inside
// llama_decode; handing out a raw pointer would make "destroy while generating"
// a use-after-free. With a shared_ptr the destructor runs when the last caller
// lets go, which is after the decode returns.
SessionPtr Lookup(jlong handle) {
    if (handle == 0) {
        return nullptr;
    }
    std::lock_guard<std::mutex> guard(g_registry_mutex);
    auto it = g_sessions.find(handle);
    return it == g_sessions.end() ? nullptr : it->second;
}

// -------------------------------------------------------------------------
// Small helpers
// -------------------------------------------------------------------------

std::string ToStdString(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return {};
    }
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

// Only ever called with text this file produced (log lines, backend names,
// error messages) -- never with model output. Model output goes back as bytes;
// see NativeGenerateNextToken.
jstring ToJavaString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

std::vector<llama_token> Tokenize(const llama_vocab* vocab, const std::string& text,
                                  bool add_special, bool parse_special) {
    if (text.empty() && !add_special) {
        return {};
    }
    const int32_t needed = -llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()),
                                           nullptr, 0, add_special, parse_special);
    if (needed <= 0) {
        return {};
    }
    std::vector<llama_token> tokens(static_cast<size_t>(needed));
    const int32_t written =
        llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), tokens.data(),
                       needed, add_special, parse_special);
    if (written < 0) {
        return {};
    }
    tokens.resize(static_cast<size_t>(written));
    return tokens;
}

std::string TokenToPiece(const llama_vocab* vocab, llama_token token, bool render_special) {
    char stack_buffer[192];
    int32_t n = llama_token_to_piece(vocab, token, stack_buffer, sizeof(stack_buffer), 0,
                                     render_special);
    if (n >= 0) {
        return std::string(stack_buffer, static_cast<size_t>(n));
    }
    std::string heap_buffer(static_cast<size_t>(-n), '\0');
    n = llama_token_to_piece(vocab, token, heap_buffer.data(),
                             static_cast<int32_t>(heap_buffer.size()), 0, render_special);
    if (n < 0) {
        return {};
    }
    heap_buffer.resize(static_cast<size_t>(n));
    return heap_buffer;
}

// Length of the longest prefix of `bytes` that ends on a complete UTF-8
// sequence.
//
// A single token is a byte sequence, not a character: one emoji is routinely
// split across two or three tokens, and handing half of a four-byte sequence to
// Kotlin produces a replacement character that never heals. Whatever this
// leaves behind is carried into the next token.
size_t CompleteUtf8Prefix(const std::string& bytes) {
    const size_t size = bytes.size();
    // A UTF-8 sequence is at most 4 bytes, so at most 3 trailing continuation
    // bytes can belong to an unfinished one.
    for (size_t back = 0; back < 4 && back < size; ++back) {
        const size_t index = size - 1 - back;
        const auto byte = static_cast<unsigned char>(bytes[index]);
        if ((byte & 0xC0u) == 0x80u) {
            continue;  // continuation byte; keep walking left for the lead byte
        }
        size_t needed = 1;
        if ((byte & 0x80u) == 0x00u) {
            needed = 1;
        } else if ((byte & 0xE0u) == 0xC0u) {
            needed = 2;
        } else if ((byte & 0xF0u) == 0xE0u) {
            needed = 3;
        } else if ((byte & 0xF8u) == 0xF0u) {
            needed = 4;
        } else {
            needed = 1;  // stray continuation or invalid lead: pass it through
        }
        return needed <= back + 1 ? size : index;
    }
    return size;
}

// Layer two of cancellation.
//
// ggml calls this between graph nodes from inside llama_decode. It is the only
// thing that can interrupt a multi-second prefill: a cooperative check between
// tokens cannot run during a call that has not returned yet.
bool AbortCallback(void* data) {
    auto* flag = static_cast<std::atomic<bool>*>(data);
    return flag != nullptr && flag->load(std::memory_order_relaxed);
}

// Wired to the same flag so that loading can be aborted the day there is a way
// to reach it. Today there is not: the flag lives on the session, the session
// handle is only published once llama_model_load_from_file has returned, and so
// nothing can set it while a load is in flight. LlamaEngine.load documents the
// consequence rather than pretending otherwise.
bool LoadProgressCallback(float /*progress*/, void* data) {
    auto* flag = static_cast<std::atomic<bool>*>(data);
    return flag == nullptr || !flag->load(std::memory_order_relaxed);
}

int64_t NowUs() { return llama_time_us(); }

// -------------------------------------------------------------------------
// Backends
// -------------------------------------------------------------------------

void NativeBackendInit(JNIEnv* /*env*/, jclass /*clazz*/) {
    llama_log_set(LlamaLogCallback, nullptr);
    llama_backend_init();
}

// GGML_BACKEND_DL=ON means each ggml backend -- and each CPU feature variant --
// is its own .so that ggml dlopens after scoring it against the running CPU.
// The scan needs a real directory, which is why packaging sets
// jniLibs.useLegacyPackaging = true: the libraries are extracted to
// applicationInfo.nativeLibraryDir at install time instead of being loaded in
// place from the APK.
//
// That choice does NOT weaken 16 KB page-size compliance. Compliance is a
// property of ELF LOAD segment alignment inside each .so (-Wl,-z,max-page-size
// =16384, set at directory scope in our CMakeLists), not of how the file is
// stored in the APK zip. Zip page alignment only matters for the
// load-uncompressed-from-APK mode, which is precisely the mode we are not in.
jint NativeLoadBackendsFromPath(JNIEnv* env, jclass /*clazz*/, jstring dir) {
    const std::string path = ToStdString(env, dir);
    if (path.empty()) {
        SetGlobalError("Backend directory was empty.");
        return 0;
    }
    ggml_backend_load_all_from_path(path.c_str());
    return static_cast<jint>(ggml_backend_reg_count());
}

// Safe mode. Loads exactly one backend .so by absolute path, skipping the scan
// that would otherwise pick the highest-scoring variant. The point is to avoid
// the i8mm/SVE/SME kernels entirely on a device where they SIGILL.
jboolean NativeLoadBackend(JNIEnv* env, jclass /*clazz*/, jstring path) {
    const std::string library = ToStdString(env, path);
    if (library.empty()) {
        SetGlobalError("Backend path was empty.");
        return JNI_FALSE;
    }
    if (ggml_backend_load(library.c_str()) == nullptr) {
        SetGlobalError("ggml_backend_load failed for " + library);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

jobjectArray NativeBackendNames(JNIEnv* env, jclass /*clazz*/) {
    const size_t count = ggml_backend_dev_count();
    jclass string_class = env->FindClass("java/lang/String");
    if (string_class == nullptr) {
        return nullptr;
    }
    jobjectArray result =
        env->NewObjectArray(static_cast<jsize>(count), string_class, nullptr);
    if (result == nullptr) {
        return nullptr;
    }
    for (size_t i = 0; i < count; ++i) {
        ggml_backend_dev_t device = ggml_backend_dev_get(i);
        std::string label = ggml_backend_dev_name(device);
        const char* description = ggml_backend_dev_description(device);
        if (description != nullptr) {
            label += " (";
            label += description;
            label += ")";
        }
        jstring value = ToJavaString(env, label);
        env->SetObjectArrayElement(result, static_cast<jsize>(i), value);
        env->DeleteLocalRef(value);
    }
    return result;
}

jstring NativeSystemInfo(JNIEnv* env, jclass /*clazz*/) {
    const char* info = llama_print_system_info();
    return ToJavaString(env, info != nullptr ? info : "");
}

// -------------------------------------------------------------------------
// Session lifecycle
// -------------------------------------------------------------------------

jlong NativeCreateSession(JNIEnv* env, jclass /*clazz*/, jstring model_path, jint context_tokens,
                          jint threads, jint batch_tokens, jboolean embedding_mode,
                          jboolean use_mmap) {
    ClearGlobalError();
    const std::string path = ToStdString(env, model_path);
    if (path.empty()) {
        SetGlobalError("Model path was empty.");
        return 0;
    }

    auto session = std::make_shared<Session>();
    session->embedding_mode = embedding_mode == JNI_TRUE;

    const int64_t load_start = NowUs();

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;  // CPU only; see docs/local-inference/backends.md
    // use_mmap was removed from the params struct; the replacement is an enum
    // that also covers mlock and direct I/O. mmap is not an optimisation on a
    // phone -- file-backed clean pages are what let the kernel evict weights
    // under pressure instead of the process holding them as dirty anonymous
    // memory.
    model_params.load_mode = use_mmap == JNI_TRUE ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;
    model_params.progress_callback = LoadProgressCallback;
    model_params.progress_callback_user_data = &session->abort;

    session->model = llama_model_load_from_file(path.c_str(), model_params);
    if (session->model == nullptr) {
        SetGlobalError("llama_model_load_from_file failed for " + path);
        return 0;
    }
    session->vocab = llama_model_get_vocab(session->model);
    session->load_us = NowUs() - load_start;

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = static_cast<uint32_t>(context_tokens > 0 ? context_tokens : 0);
    ctx_params.n_batch = static_cast<uint32_t>(batch_tokens > 0 ? batch_tokens : 512);
    ctx_params.n_ubatch = ctx_params.n_batch;
    ctx_params.n_threads = threads > 0 ? threads : 4;
    ctx_params.n_threads_batch = ctx_params.n_threads;
    ctx_params.embeddings = session->embedding_mode;
    ctx_params.pooling_type =
        session->embedding_mode ? LLAMA_POOLING_TYPE_MEAN : LLAMA_POOLING_TYPE_UNSPECIFIED;
    ctx_params.no_perf = true;
    ctx_params.abort_callback = AbortCallback;
    ctx_params.abort_callback_data = &session->abort;

    session->ctx = llama_init_from_model(session->model, ctx_params);
    if (session->ctx == nullptr) {
        SetGlobalError("llama_init_from_model failed; the context did not fit.");
        return 0;
    }

    session->n_ctx = static_cast<int>(llama_n_ctx(session->ctx));
    session->n_batch = static_cast<int>(llama_n_batch(session->ctx));
    session->batch = llama_batch_init(session->n_batch, 0, 1);
    session->batch_allocated = true;

    // Not fatal: a model with no template still runs, the caller just has to
    // build the prompt itself. Rendering is the caller's decision, not ours.
    try {
        session->templates = common_chat_templates_init(session->model, "");
    } catch (const std::exception& error) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "chat template unavailable: %s", error.what());
        session->templates.reset();
    }

    std::lock_guard<std::mutex> guard(g_registry_mutex);
    const jlong handle = g_next_handle++;
    g_sessions.emplace(handle, std::move(session));
    return handle;
}

void NativeDestroySession(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    SessionPtr session;
    {
        std::lock_guard<std::mutex> guard(g_registry_mutex);
        auto it = g_sessions.find(handle);
        if (it == g_sessions.end()) {
            return;
        }
        session = it->second;
        g_sessions.erase(it);
    }
    // Stop any decode in flight first, then wait for it. Freeing a context out
    // from under llama_decode is a native crash; dropping the last shared_ptr
    // after the generating thread has let go is not.
    session->abort.store(true, std::memory_order_relaxed);
    {
        std::lock_guard<std::mutex> guard(session->lock);
        session->generating = false;
    }
}

jint NativeContextSize(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    SessionPtr session = Lookup(handle);
    return session == nullptr ? 0 : static_cast<jint>(session->n_ctx);
}

jstring NativeLastError(JNIEnv* env, jclass /*clazz*/, jlong handle) {
    SessionPtr session = Lookup(handle);
    std::string message;
    if (session != nullptr) {
        std::lock_guard<std::mutex> guard(session->lock);
        message = session->last_error;
    }
    if (message.empty()) {
        message = CurrentGlobalError();
    }
    return message.empty() ? nullptr : ToJavaString(env, message);
}

// -------------------------------------------------------------------------
// Prompting
// -------------------------------------------------------------------------

// Renders a chat history through the model's own Jinja template.
//
// Parallel arrays rather than JSON: this module would otherwise need a JSON
// codec on both sides of the boundary to move four fields. Tool specifications
// are deliberately not plumbed through yet -- they are JSON Schema documents,
// which is the point at which a codec becomes worth it.
jstring NativeApplyChatTemplate(JNIEnv* env, jclass /*clazz*/, jlong handle, jobjectArray roles,
                                jobjectArray contents, jboolean add_assistant,
                                jboolean enable_thinking) {
    SessionPtr session = Lookup(handle);
    if (session == nullptr || session->templates == nullptr) {
        SetGlobalError("No chat template is available for this model.");
        return nullptr;
    }
    const jsize count = env->GetArrayLength(roles);
    if (count != env->GetArrayLength(contents)) {
        SetGlobalError("Roles and contents differ in length.");
        return nullptr;
    }

    common_chat_templates_inputs inputs;
    inputs.use_jinja = true;
    inputs.add_generation_prompt = add_assistant == JNI_TRUE;
    inputs.enable_thinking = enable_thinking == JNI_TRUE;
    // These two mean "the tokenizer will add BOS/EOS, so strip a leading or
    // trailing one from the rendered text". Getting them wrong is the classic
    // double-BOS bug: the model sees two begin-of-sequence tokens, output
    // quality drops, and nothing anywhere reports an error.
    inputs.add_bos = llama_vocab_get_add_bos(session->vocab);
    inputs.add_eos = llama_vocab_get_add_eos(session->vocab);
    inputs.messages.reserve(static_cast<size_t>(count));

    for (jsize i = 0; i < count; ++i) {
        auto role = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
        auto content = static_cast<jstring>(env->GetObjectArrayElement(contents, i));
        common_chat_msg message;
        message.role = ToStdString(env, role);
        message.content = ToStdString(env, content);
        inputs.messages.push_back(std::move(message));
        env->DeleteLocalRef(role);
        env->DeleteLocalRef(content);
    }

    try {
        common_chat_params params = common_chat_templates_apply(session->templates.get(), inputs);
        return ToJavaString(env, params.prompt);
    } catch (const std::exception& error) {
        SetGlobalError(std::string("Chat template rendering failed: ") + error.what());
        return nullptr;
    }
}

jint NativeTokenCount(JNIEnv* env, jclass /*clazz*/, jlong handle, jstring text) {
    SessionPtr session = Lookup(handle);
    if (session == nullptr) {
        return -1;
    }
    const std::string value = ToStdString(env, text);
    return static_cast<jint>(Tokenize(session->vocab, value, true, true).size());
}

// -------------------------------------------------------------------------
// Sampling
// -------------------------------------------------------------------------

void NativeConfigureSampler(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jfloat temperature,
                            jfloat top_p, jint top_k, jfloat min_p, jfloat repeat_penalty,
                            jint repeat_last_n, jlong seed) {
    SessionPtr session = Lookup(handle);
    if (session == nullptr) {
        return;
    }
    std::lock_guard<std::mutex> guard(session->lock);

    if (session->sampler != nullptr) {
        llama_sampler_free(session->sampler);
        session->sampler = nullptr;
    }

    llama_sampler_chain_params chain_params = llama_sampler_chain_default_params();
    chain_params.no_perf = true;
    llama_sampler* chain = llama_sampler_chain_init(chain_params);

    // Order is not cosmetic: penalties operate on the full distribution, the
    // truncations narrow it, temperature reshapes what is left, and exactly one
    // selector ends the chain.
    if (repeat_penalty != 1.0F && repeat_last_n != 0) {
        llama_sampler_chain_add(
            chain, llama_sampler_init_penalties(repeat_last_n, repeat_penalty, 0.0F, 0.0F));
    }
    if (top_k > 0) {
        llama_sampler_chain_add(chain, llama_sampler_init_top_k(top_k));
    }
    if (top_p > 0.0F && top_p < 1.0F) {
        llama_sampler_chain_add(chain, llama_sampler_init_top_p(top_p, 1));
    }
    if (min_p > 0.0F) {
        llama_sampler_chain_add(chain, llama_sampler_init_min_p(min_p, 1));
    }
    if (temperature <= 0.0F) {
        // Temperature 0 is a request for determinism, and the honest way to
        // honour it is greedy selection rather than a temperature of 0.0001.
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
        const auto resolved_seed = seed < 0 ? LLAMA_DEFAULT_SEED : static_cast<uint32_t>(seed);
        llama_sampler_chain_add(chain, llama_sampler_init_dist(resolved_seed));
    }

    session->sampler = chain;
}

// -------------------------------------------------------------------------
// Generation -- the pull model
// -------------------------------------------------------------------------
//
// WHY PULL AND NOT PUSH
//
// The obvious design is a callback: hand native code a Java object and have it
// invoke onToken() as each token appears. It is worse here in a way that only
// shows up under load.
//
// ggml runs llama_decode on its own worker threads, none of which is attached
// to the JVM. Calling back means AttachCurrentThread on every one of them, a
// GlobalRef on the listener that has to outlive the generation and be deleted
// on exactly one path, and a JNIEnv that must never leak across threads. Every
// one of those is a crash rather than an exception when it is wrong, and the
// crash is in a thread with no Java frames, so the tombstone names ggml.
//
// Pulling inverts it. A dedicated OS thread on the Kotlin side calls
// nativeGenerateNextToken in a loop and offers each result into a Flow. Native
// code never touches the JVM outside a JNI call it was invoked from, there is
// no GlobalRef, no attach, no detach, and cancellation is a flag rather than a
// listener lifecycle. The cost is one thread per engine, which we want anyway
// because llama.cpp's context is not thread-safe.

jboolean NativeStartGeneration(JNIEnv* env, jclass /*clazz*/, jlong handle, jstring prompt,
                               jint max_tokens) {
    SessionPtr session = Lookup(handle);
    if (session == nullptr) {
        SetGlobalError("Unknown session handle.");
        return JNI_FALSE;
    }
    const std::string text = ToStdString(env, prompt);

    std::lock_guard<std::mutex> guard(session->lock);
    if (session->embedding_mode) {
        session->last_error = "This session was created for embeddings, not generation.";
        return JNI_FALSE;
    }
    if (session->sampler == nullptr) {
        session->last_error = "nativeConfigureSampler must be called before generating.";
        return JNI_FALSE;
    }

    // A fresh turn starts from a clear cache. Prefix reuse is a real
    // optimisation and deliberately not attempted here: getting it subtly wrong
    // produces a model that answers a question the user did not ask, and there
    // is no device to measure the gain on.
    llama_memory_clear(llama_get_memory(session->ctx), true);
    llama_sampler_reset(session->sampler);

    session->pending = Tokenize(session->vocab, text, true, true);
    if (session->pending.empty()) {
        session->last_error = "The prompt tokenized to nothing.";
        return JNI_FALSE;
    }
    if (static_cast<int>(session->pending.size()) >= session->n_ctx) {
        session->last_error = "The prompt is longer than the context window.";
        return JNI_FALSE;
    }

    session->abort.store(false, std::memory_order_relaxed);
    session->utf8_carry.clear();
    session->n_past = 0;
    session->generated = 0;
    session->prompt_tokens = static_cast<int>(session->pending.size());
    session->prefill_us = 0;
    session->eval_us = 0;
    session->finish_reason = kFinishRunning;
    session->last_error.clear();
    session->max_tokens = max_tokens > 0 ? max_tokens : session->n_ctx;
    session->generating = true;
    return JNI_TRUE;
}

// Decodes everything queued in session->pending. Returns a finish code, or
// kFinishRunning when the queue drained normally.
jint DecodePending(Session& session) {
    const size_t total = session.pending.size();
    size_t offset = 0;
    const int64_t started = NowUs();

    while (offset < total) {
        // Layer one of cancellation: a cooperative check between decode calls.
        // It cannot help inside a single multi-second prefill, which is exactly
        // what the abort callback is for.
        if (session.abort.load(std::memory_order_relaxed)) {
            return kFinishCancelled;
        }

        const auto chunk =
            static_cast<int32_t>(std::min<size_t>(total - offset, static_cast<size_t>(session.n_batch)));
        session.batch.n_tokens = chunk;
        for (int32_t i = 0; i < chunk; ++i) {
            session.batch.token[i] = session.pending[offset + static_cast<size_t>(i)];
            session.batch.pos[i] = session.n_past + i;
            session.batch.n_seq_id[i] = 1;
            session.batch.seq_id[i][0] = 0;
            session.batch.logits[i] = 0;
        }
        const bool last_chunk = offset + static_cast<size_t>(chunk) == total;
        if (last_chunk) {
            session.batch.logits[chunk - 1] = 1;
        }

        const int32_t status = llama_decode(session.ctx, session.batch);
        if (status != 0) {
            switch (status) {
                case 1:
                    // No KV slot: the cache is full. Truncation, not a fault.
                    return kFinishLength;
                case 2:
                    return kFinishCancelled;
                default:
                    session.last_error = "llama_decode failed with status " + std::to_string(status);
                    return kFinishError;
            }
        }
        session.n_past += chunk;
        offset += static_cast<size_t>(chunk);
    }

    const int64_t elapsed = NowUs() - started;
    if (session.generated == 0) {
        session.prefill_us += elapsed;
    } else {
        session.eval_us += elapsed;
    }
    session.pending.clear();
    return kFinishRunning;
}

// Returns the next token's bytes, or null when generation is over.
//
// BYTES AND NOT A String: NewStringUTF takes *modified* UTF-8, in which a
// character outside the BMP is a six-byte surrogate pair rather than the
// four-byte sequence real UTF-8 uses. Model output is full of emoji. Handing
// four-byte sequences to NewStringUTF is undefined behaviour that ART
// occasionally turns into an abort, so the bytes cross the boundary raw and
// Kotlin decodes them.
//
// An empty array is a valid, non-terminal result: it means this token completed
// no code point and its bytes are being carried into the next one.
jbyteArray NativeGenerateNextToken(JNIEnv* env, jclass /*clazz*/, jlong handle) {
    SessionPtr session = Lookup(handle);
    if (session == nullptr) {
        return nullptr;
    }
    std::lock_guard<std::mutex> guard(session->lock);
    if (!session->generating) {
        return nullptr;
    }
    if (session->abort.load(std::memory_order_relaxed)) {
        session->generating = false;
        session->finish_reason = kFinishCancelled;
        return nullptr;
    }

    const jint decode_status = DecodePending(*session);
    if (decode_status != kFinishRunning) {
        session->generating = false;
        session->finish_reason = decode_status;
        return nullptr;
    }

    const llama_token token = llama_sampler_sample(session->sampler, session->ctx, -1);
    if (llama_vocab_is_eog(session->vocab, token)) {
        session->generating = false;
        session->finish_reason = kFinishStop;
        return nullptr;
    }

    session->generated += 1;
    session->pending.assign(1, token);

    // render_special = false: control tokens are protocol, not answer text.
    session->utf8_carry += TokenToPiece(session->vocab, token, false);
    const size_t emit = CompleteUtf8Prefix(session->utf8_carry);

    if (session->generated >= session->max_tokens || session->n_past + 1 >= session->n_ctx) {
        // Emit this token, then stop on the next call. The finish reason is set
        // now so the caller sees LENGTH rather than a stream that just ended.
        session->generating = false;
        session->finish_reason = kFinishLength;
    }

    jbyteArray result = env->NewByteArray(static_cast<jsize>(emit));
    if (result == nullptr) {
        return nullptr;
    }
    if (emit > 0) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(emit),
                                reinterpret_cast<const jbyte*>(session->utf8_carry.data()));
        session->utf8_carry.erase(0, emit);
    }
    return result;
}

// Deliberately does not take session->lock: the point of this call is to be
// answerable while the engine thread is blocked inside llama_decode holding
// that very lock. Setting the atomic is what the abort callback reads.
void NativeRequestAbort(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    SessionPtr session = Lookup(handle);
    if (session != nullptr) {
        session->abort.store(true, std::memory_order_relaxed);
    }
}

jint NativeFinishReason(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    SessionPtr session = Lookup(handle);
    if (session == nullptr) {
        return kFinishError;
    }
    std::lock_guard<std::mutex> guard(session->lock);
    return session->finish_reason;
}

// [promptTokens, completionTokens, promptEvalNanos, evalNanos, loadNanos].
// A flat array rather than a struct: six GetFieldIDs against a Kotlin data
// class is six more names that R8 can rename.
jlongArray NativeStats(JNIEnv* env, jclass /*clazz*/, jlong handle) {
    SessionPtr session = Lookup(handle);
    if (session == nullptr) {
        return nullptr;
    }
    jlong values[5];
    {
        std::lock_guard<std::mutex> guard(session->lock);
        values[0] = session->prompt_tokens;
        values[1] = session->generated;
        values[2] = session->prefill_us * 1000;
        values[3] = session->eval_us * 1000;
        values[4] = session->load_us * 1000;
    }
    jlongArray result = env->NewLongArray(5);
    if (result == nullptr) {
        return nullptr;
    }
    env->SetLongArrayRegion(result, 0, 5, values);
    return result;
}

// -------------------------------------------------------------------------
// Embeddings
// -------------------------------------------------------------------------

jfloatArray NativeEmbed(JNIEnv* env, jclass /*clazz*/, jlong handle, jstring text) {
    SessionPtr session = Lookup(handle);
    if (session == nullptr) {
        return nullptr;
    }
    const std::string value = ToStdString(env, text);

    std::lock_guard<std::mutex> guard(session->lock);
    if (!session->embedding_mode) {
        session->last_error = "This session was not created for embeddings.";
        return nullptr;
    }

    std::vector<llama_token> tokens = Tokenize(session->vocab, value, true, false);
    if (tokens.empty()) {
        session->last_error = "The text tokenized to nothing.";
        return nullptr;
    }
    if (static_cast<int>(tokens.size()) > session->n_ctx) {
        tokens.resize(static_cast<size_t>(session->n_ctx));
    }

    llama_memory_clear(llama_get_memory(session->ctx), true);
    session->abort.store(false, std::memory_order_relaxed);

    const auto count = static_cast<int32_t>(tokens.size());
    session->batch.n_tokens = count;
    for (int32_t i = 0; i < count; ++i) {
        session->batch.token[i] = tokens[static_cast<size_t>(i)];
        session->batch.pos[i] = i;
        session->batch.n_seq_id[i] = 1;
        session->batch.seq_id[i][0] = 0;
        session->batch.logits[i] = 1;  // pooling needs every token's output
    }

    if (llama_decode(session->ctx, session->batch) != 0) {
        session->last_error = "llama_decode failed while embedding.";
        return nullptr;
    }

    const float* embeddings = llama_get_embeddings_seq(session->ctx, 0);
    if (embeddings == nullptr) {
        embeddings = llama_get_embeddings_ith(session->ctx, -1);
    }
    if (embeddings == nullptr) {
        session->last_error = "The model produced no embeddings.";
        return nullptr;
    }

    const auto dimensions = static_cast<jsize>(llama_model_n_embd(session->model));
    jfloatArray result = env->NewFloatArray(dimensions);
    if (result == nullptr) {
        return nullptr;
    }
    env->SetFloatArrayRegion(result, 0, dimensions, embeddings);
    return result;
}

// -------------------------------------------------------------------------
// LoRA
// -------------------------------------------------------------------------
//
// llama_set_adapters_lora takes an array of adapters and a parallel array of
// scales, so the JNI signature is plural from the start. Stacking two adapters
// at 0.7 and 0.3 is an ordinary thing to want, and a single-adapter signature
// would have to be broken later to allow it.
jboolean NativeSetLoraAdapters(JNIEnv* env, jclass /*clazz*/, jlong handle, jobjectArray paths,
                               jfloatArray scales) {
    SessionPtr session = Lookup(handle);
    if (session == nullptr) {
        return JNI_FALSE;
    }
    const jsize count = paths == nullptr ? 0 : env->GetArrayLength(paths);
    if (scales != nullptr && env->GetArrayLength(scales) != count) {
        SetGlobalError("LoRA paths and scales differ in length.");
        return JNI_FALSE;
    }

    std::lock_guard<std::mutex> guard(session->lock);

    if (count == 0) {
        llama_set_adapters_lora(session->ctx, nullptr, 0, nullptr);
        return JNI_TRUE;
    }

    std::vector<llama_adapter_lora*> adapters;
    std::vector<float> weights(static_cast<size_t>(count), 1.0F);
    adapters.reserve(static_cast<size_t>(count));
    if (scales != nullptr) {
        env->GetFloatArrayRegion(scales, 0, count, weights.data());
    }

    for (jsize i = 0; i < count; ++i) {
        auto path = static_cast<jstring>(env->GetObjectArrayElement(paths, i));
        const std::string file = ToStdString(env, path);
        env->DeleteLocalRef(path);
        llama_adapter_lora* adapter = llama_adapter_lora_init(session->model, file.c_str());
        if (adapter == nullptr) {
            session->last_error = "Failed to load LoRA adapter " + file;
            return JNI_FALSE;
        }
        adapters.push_back(adapter);
    }

    const int32_t status = llama_set_adapters_lora(session->ctx, adapters.data(),
                                                   adapters.size(), weights.data());
    if (status != 0) {
        session->last_error = "llama_set_adapters_lora failed with status " +
                              std::to_string(status);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

// -------------------------------------------------------------------------
// Registration
// -------------------------------------------------------------------------

const JNINativeMethod kMethods[] = {
    {"nativeBackendInit", "()V", reinterpret_cast<void*>(NativeBackendInit)},
    {"nativeLoadBackendsFromPath", "(Ljava/lang/String;)I",
     reinterpret_cast<void*>(NativeLoadBackendsFromPath)},
    {"nativeLoadBackend", "(Ljava/lang/String;)Z", reinterpret_cast<void*>(NativeLoadBackend)},
    {"nativeBackendNames", "()[Ljava/lang/String;", reinterpret_cast<void*>(NativeBackendNames)},
    {"nativeSystemInfo", "()Ljava/lang/String;", reinterpret_cast<void*>(NativeSystemInfo)},
    {"nativeCreateSession", "(Ljava/lang/String;IIIZZ)J",
     reinterpret_cast<void*>(NativeCreateSession)},
    {"nativeDestroySession", "(J)V", reinterpret_cast<void*>(NativeDestroySession)},
    {"nativeContextSize", "(J)I", reinterpret_cast<void*>(NativeContextSize)},
    {"nativeLastError", "(J)Ljava/lang/String;", reinterpret_cast<void*>(NativeLastError)},
    {"nativeApplyChatTemplate", "(J[Ljava/lang/String;[Ljava/lang/String;ZZ)Ljava/lang/String;",
     reinterpret_cast<void*>(NativeApplyChatTemplate)},
    {"nativeTokenCount", "(JLjava/lang/String;)I", reinterpret_cast<void*>(NativeTokenCount)},
    {"nativeConfigureSampler", "(JFFIFFIJ)V", reinterpret_cast<void*>(NativeConfigureSampler)},
    {"nativeStartGeneration", "(JLjava/lang/String;I)Z",
     reinterpret_cast<void*>(NativeStartGeneration)},
    {"nativeGenerateNextToken", "(J)[B", reinterpret_cast<void*>(NativeGenerateNextToken)},
    {"nativeRequestAbort", "(J)V", reinterpret_cast<void*>(NativeRequestAbort)},
    {"nativeFinishReason", "(J)I", reinterpret_cast<void*>(NativeFinishReason)},
    {"nativeStats", "(J)[J", reinterpret_cast<void*>(NativeStats)},
    {"nativeEmbed", "(JLjava/lang/String;)[F", reinterpret_cast<void*>(NativeEmbed)},
    {"nativeSetLoraAdapters", "(J[Ljava/lang/String;[F)Z",
     reinterpret_cast<void*>(NativeSetLoraAdapters)},
};

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass bridge = env->FindClass(kBridgeClass);
    if (bridge == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "FindClass failed for %s", kBridgeClass);
        return JNI_ERR;
    }
    const jint count = static_cast<jint>(sizeof(kMethods) / sizeof(kMethods[0]));
    if (env->RegisterNatives(bridge, kMethods, count) != JNI_OK) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "RegisterNatives failed");
        return JNI_ERR;
    }
    env->DeleteLocalRef(bridge);
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* /*vm*/, void* /*reserved*/) {
    std::lock_guard<std::mutex> guard(g_registry_mutex);
    g_sessions.clear();
}
