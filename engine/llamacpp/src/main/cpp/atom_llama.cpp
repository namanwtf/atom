// Minimal JNI bridge between atom.engine.llamacpp.LlamaNative and the llama.cpp C API.
//
// All strings cross the boundary as UTF-8 byte arrays (never NewStringUTF / GetStringUTFChars,
// whose "modified UTF-8" mangles characters outside the BMP such as emoji).

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "atom_llama"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Session {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    llama_batch_ext * batch = nullptr;
    int32_t n_batch = 0;
    std::vector<llama_token> cached;   // tokens currently held in the KV cache (sequence 0)
    std::atomic<bool> cancel{false};
};

enum Finish : jint { kStop = 0, kLength = 1, kCancelled = 2 };

void throw_java(JNIEnv * env, const std::string & msg) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) env->ThrowNew(cls, msg.c_str());
}

std::string to_string(JNIEnv * env, jbyteArray bytes) {
    if (!bytes) return {};
    const jsize n = env->GetArrayLength(bytes);
    std::string out(static_cast<size_t>(n), '\0');
    if (n > 0) env->GetByteArrayRegion(bytes, 0, n, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

jbyteArray to_bytes(JNIEnv * env, const char * data, size_t n) {
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(n));
    if (arr && n > 0) env->SetByteArrayRegion(arr, 0, static_cast<jsize>(n), reinterpret_cast<const jbyte *>(data));
    return arr;
}

void log_callback(ggml_log_level level, const char * text, void *) {
    if (level == GGML_LOG_LEVEL_ERROR) {
        __android_log_write(ANDROID_LOG_ERROR, LOG_TAG, text);
    } else if (level == GGML_LOG_LEVEL_WARN) {
        __android_log_write(ANDROID_LOG_WARN, LOG_TAG, text);
    }
}

void init_backend_once() {
    static std::once_flag once;
    std::call_once(once, [] {
        llama_log_set(log_callback, nullptr);
        llama_backend_init();
    });
}

bool abort_cb(void * data) {
    return static_cast<Session *>(data)->cancel.load(std::memory_order_relaxed);
}

// Length of the longest prefix of `s` that does not end inside a multibyte UTF-8 sequence.
size_t utf8_complete_prefix(const std::string & s) {
    const size_t n = s.size();
    // Look back at most 3 bytes for the lead byte of the last sequence.
    for (size_t back = 1; back <= 4 && back <= n; ++back) {
        const auto c = static_cast<unsigned char>(s[n - back]);
        if ((c & 0xC0) == 0x80) continue;   // continuation byte, keep looking
        size_t need = 1;
        if ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        return back >= need ? n : n - back;
    }
    return n; // garbage (too many continuation bytes): pass through, Kotlin substitutes U+FFFD
}

bool token_piece(const llama_vocab * vocab, llama_token tok, std::string & out) {
    char buf[256];
    int32_t n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, /*special=*/false);
    if (n >= 0) {
        out.assign(buf, static_cast<size_t>(n));
        return true;
    }
    std::vector<char> big(static_cast<size_t>(-n));
    n = llama_token_to_piece(vocab, tok, big.data(), static_cast<int32_t>(big.size()), 0, false);
    if (n < 0) return false;
    out.assign(big.data(), static_cast<size_t>(n));
    return true;
}

// Decodes tokens[from, to) at positions [from, to). Logits are requested for the last token only
// when `want_logits`. Returns llama_process' status (0 = ok, 2 = aborted).
int32_t decode_range(Session * s, const std::vector<llama_token> & tokens, size_t from, size_t to, bool want_logits) {
    for (size_t i = from; i < to; i += static_cast<size_t>(s->n_batch)) {
        if (s->cancel.load(std::memory_order_relaxed)) return 2;
        const size_t end = std::min(to, i + static_cast<size_t>(s->n_batch));
        llama_batch_ext_clear(s->batch);
        int32_t last_idx = -1;
        for (size_t j = i; j < end; ++j) {
            last_idx = llama_batch_ext_add_token(s->batch, 0, tokens[j]);
            if (last_idx < 0) return -1;
            const llama_pos pos = static_cast<llama_pos>(j);
            llama_batch_ext_set_pos(s->batch, last_idx, &pos);
        }
        if (want_logits && end == to) llama_batch_ext_set_output_logits(s->batch, last_idx, true);
        const int32_t rc = llama_process(s->ctx, LLAMA_PROCESS_TYPE_DECODE, s->batch);
        if (rc != 0) return rc;
        s->cached.insert(s->cached.end(), tokens.begin() + static_cast<long>(i), tokens.begin() + static_cast<long>(end));
    }
    return 0;
}

// After a failed/aborted decode, make `cached` match what the KV cache really holds.
void resync_cache(Session * s) {
    llama_memory_t mem = llama_get_memory(s->ctx);
    const llama_pos max_pos = llama_memory_seq_pos_max(mem, 0);
    const size_t keep = max_pos < 0 ? 0 : static_cast<size_t>(max_pos) + 1;
    if (keep <= s->cached.size()) {
        s->cached.resize(keep);
    } else {
        llama_memory_clear(mem, true);
        s->cached.clear();
    }
}

void free_session(Session * s) {
    if (!s) return;
    if (s->batch) llama_batch_ext_free(s->batch);
    if (s->ctx) llama_free(s->ctx);
    if (s->model) llama_model_free(s->model);
    delete s;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_atom_engine_llamacpp_LlamaNative_nativeLoad(JNIEnv * env, jclass, jbyteArray jpath, jint n_ctx,
                                                 jint n_threads, jint n_gpu_layers) {
    init_backend_once();
    const std::string path = to_string(env, jpath);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = n_gpu_layers;
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;   // mmap on, mlock off: pages stay reclaimable by the OS

    auto * s = new Session();
    s->model = llama_model_load_from_file(path.c_str(), mp);
    if (!s->model) {
        free_session(s);
        throw_java(env, "llama.cpp could not load the model (corrupt, truncated or unsupported GGUF)");
        return 0;
    }
    s->vocab = llama_model_get_vocab(s->model);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(n_ctx);
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_seq_max = 1;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
    cp.no_perf = true;
    cp.abort_callback = abort_cb;
    cp.abort_callback_data = s;

    // q8_0 K/V halves KV memory traffic. A quantized V cache needs flash attention; fall back
    // gracefully if this model/backend cannot do it.
    const ggml_type kv_try[][2] = {
        {GGML_TYPE_Q8_0, GGML_TYPE_Q8_0},
        {GGML_TYPE_Q8_0, GGML_TYPE_F16},
        {GGML_TYPE_F16, GGML_TYPE_F16},
    };
    for (const auto & kv : kv_try) {
        cp.type_k = kv[0];
        cp.type_v = kv[1];
        s->ctx = llama_init_from_model(s->model, cp);
        if (s->ctx) break;
        LOGW("context creation failed with K=%d V=%d, retrying", kv[0], kv[1]);
    }
    if (!s->ctx) {
        free_session(s);
        throw_java(env, "llama.cpp could not create a context (not enough memory for this context window?)");
        return 0;
    }
    s->n_batch = static_cast<int32_t>(cp.n_batch);
    s->batch = llama_batch_ext_init(s->ctx);
    if (!s->batch) {
        free_session(s);
        throw_java(env, "llama.cpp could not allocate a batch");
        return 0;
    }
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT jint JNICALL
Java_atom_engine_llamacpp_LlamaNative_nativeContextSize(JNIEnv *, jclass, jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    return s ? static_cast<jint>(llama_n_ctx(s->ctx)) : 0;
}

JNIEXPORT jbyteArray JNICALL
Java_atom_engine_llamacpp_LlamaNative_nativeFormatChat(JNIEnv * env, jclass, jlong handle,
                                                       jobjectArray jroles, jobjectArray jcontents) {
    auto * s = reinterpret_cast<Session *>(handle);
    const jsize n = env->GetArrayLength(jroles);
    std::vector<std::string> roles(static_cast<size_t>(n)), contents(static_cast<size_t>(n));
    size_t total = 0;
    for (jsize i = 0; i < n; ++i) {
        auto jr = static_cast<jbyteArray>(env->GetObjectArrayElement(jroles, i));
        auto jc = static_cast<jbyteArray>(env->GetObjectArrayElement(jcontents, i));
        roles[i] = to_string(env, jr);
        contents[i] = to_string(env, jc);
        env->DeleteLocalRef(jr);
        env->DeleteLocalRef(jc);
        total += contents[i].size() + roles[i].size();
    }
    std::vector<llama_chat_message> msgs(static_cast<size_t>(n));
    for (size_t i = 0; i < msgs.size(); ++i) msgs[i] = {roles[i].c_str(), contents[i].c_str()};

    // The model's embedded template, matched against llama.cpp's built-in template list.
    const char * tmpl = llama_model_chat_template(s->model, nullptr);
    std::vector<char> buf(2 * total + 1024);
    int32_t len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), static_cast<int32_t>(buf.size()));
    if (len < 0 && tmpl != nullptr) {
        LOGW("embedded chat template not recognised by llama.cpp, falling back to chatml");
        tmpl = "chatml";
        len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), static_cast<int32_t>(buf.size()));
    }
    if (len < 0) {
        throw_java(env, "could not apply a chat template for this model");
        return nullptr;
    }
    if (static_cast<size_t>(len) > buf.size()) {
        buf.resize(static_cast<size_t>(len));
        len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), static_cast<int32_t>(buf.size()));
    }
    return to_bytes(env, buf.data(), static_cast<size_t>(len));
}

// Returns [promptTokens, generatedTokens, finish(0 stop, 1 length, 2 cancelled)].
JNIEXPORT jintArray JNICALL
Java_atom_engine_llamacpp_LlamaNative_nativeGenerate(JNIEnv * env, jclass, jlong handle, jbyteArray jprompt,
                                                     jint max_tokens, jfloat temperature, jobject sink) {
    auto * s = reinterpret_cast<Session *>(handle);
    s->cancel.store(false);

    jclass sink_cls = env->GetObjectClass(sink);
    jmethodID on_token = env->GetMethodID(sink_cls, "onToken", "([B)Z");
    env->DeleteLocalRef(sink_cls);
    if (!on_token) return nullptr; // NoSuchMethodError pending

    // ---- tokenize ----
    const std::string prompt = to_string(env, jprompt);
    int32_t n_tok = -llama_tokenize(s->vocab, prompt.data(), static_cast<int32_t>(prompt.size()), nullptr, 0, true, true);
    if (n_tok <= 0) {
        throw_java(env, "failed to tokenize the prompt");
        return nullptr;
    }
    std::vector<llama_token> tokens(static_cast<size_t>(n_tok));
    n_tok = llama_tokenize(s->vocab, prompt.data(), static_cast<int32_t>(prompt.size()), tokens.data(), n_tok, true, true);
    if (n_tok < 0) {
        throw_java(env, "failed to tokenize the prompt");
        return nullptr;
    }
    tokens.resize(static_cast<size_t>(n_tok));
    // Templates that already spell out BOS plus add_bos would give a double BOS.
    const llama_token bos = llama_vocab_bos(s->vocab);
    if (tokens.size() >= 2 && tokens[0] == bos && tokens[1] == bos) tokens.erase(tokens.begin());

    const auto n_ctx = static_cast<size_t>(llama_n_ctx(s->ctx));
    if (tokens.size() + 1 >= n_ctx) {
        throw_java(env, "prompt is " + std::to_string(tokens.size()) + " tokens but the context window is " +
                        std::to_string(n_ctx));
        return nullptr;
    }

    // ---- reuse the longest cached prefix, decode the rest ----
    llama_memory_t mem = llama_get_memory(s->ctx);
    size_t common = 0;
    while (common < s->cached.size() && common < tokens.size() && s->cached[common] == tokens[common]) ++common;
    if (common == tokens.size()) --common;   // always decode at least one token to get fresh logits
    if (!llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(common), -1)) {
        llama_memory_clear(mem, true);       // e.g. recurrent models cannot drop a partial range
        common = 0;
    }
    s->cached.resize(common);

    const int32_t prc = decode_range(s, tokens, common, tokens.size(), true);
    if (prc != 0) {
        resync_cache(s);
        if (prc == 2) {
            jint r[3] = {static_cast<jint>(tokens.size()), 0, kCancelled};
            jintArray out = env->NewIntArray(3);
            env->SetIntArrayRegion(out, 0, 3, r);
            return out;
        }
        throw_java(env, "llama.cpp failed to process the prompt (code " + std::to_string(prc) + ")");
        return nullptr;
    }

    // ---- sampler chain ----
    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    sp.no_perf = true;
    llama_sampler * smpl = llama_sampler_chain_init(sp);
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        // min-p first: an O(n) cut that leaves top-p only a handful of candidates to sort.
        llama_sampler_chain_add(smpl, llama_sampler_init_min_p(0.05f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    // ---- decode loop ----
    jint finish = kStop;
    jint generated = 0;
    std::string pending;   // bytes of an incomplete UTF-8 sequence carried to the next token
    std::string piece;
    bool failed = false;
    std::string error;

    auto emit = [&](const std::string & bytes) -> bool {
        if (bytes.empty()) return true;
        jbyteArray arr = to_bytes(env, bytes.data(), bytes.size());
        const jboolean keep_going = env->CallBooleanMethod(sink, on_token, arr);
        env->DeleteLocalRef(arr);
        if (env->ExceptionCheck()) return false;
        return keep_going == JNI_TRUE;
    };

    while (true) {
        if (s->cancel.load(std::memory_order_relaxed)) { finish = kCancelled; break; }
        if (generated >= max_tokens) { finish = kLength; break; }

        const llama_token tok = llama_sampler_sample(smpl, s->ctx, -1);
        if (llama_vocab_is_eog(s->vocab, tok)) break;
        ++generated;

        if (!token_piece(s->vocab, tok, piece)) piece.clear();
        pending += piece;
        const size_t ready = utf8_complete_prefix(pending);
        if (ready > 0) {
            const std::string out = pending.substr(0, ready);
            pending.erase(0, ready);
            if (!emit(out)) { finish = kCancelled; break; }
        }

        if (generated >= max_tokens) { finish = kLength; break; }
        if (s->cached.size() + 1 >= n_ctx) { finish = kLength; break; }   // context full

        llama_batch_ext_clear(s->batch);
        const int32_t idx = llama_batch_ext_add_token(s->batch, 0, tok);
        const llama_pos pos = static_cast<llama_pos>(s->cached.size());
        llama_batch_ext_set_pos(s->batch, idx, &pos);
        llama_batch_ext_set_output_logits(s->batch, idx, true);
        const int32_t rc = llama_process(s->ctx, LLAMA_PROCESS_TYPE_DECODE, s->batch);
        if (rc != 0) {
            resync_cache(s);
            if (rc == 2) { finish = kCancelled; break; }
            failed = true;
            error = "llama.cpp decode failed (code " + std::to_string(rc) + ")";
            break;
        }
        s->cached.push_back(tok);
    }
    if (!pending.empty() && !env->ExceptionCheck() && finish != kCancelled) emit(pending);

    llama_sampler_free(smpl);

    if (env->ExceptionCheck()) return nullptr;   // exception thrown by the Kotlin sink
    if (failed) {
        throw_java(env, error);
        return nullptr;
    }
    jint r[3] = {static_cast<jint>(tokens.size()), generated, finish};
    jintArray out = env->NewIntArray(3);
    env->SetIntArrayRegion(out, 0, 3, r);
    return out;
}

JNIEXPORT void JNICALL
Java_atom_engine_llamacpp_LlamaNative_nativeCancel(JNIEnv *, jclass, jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (s) s->cancel.store(true);
}

JNIEXPORT void JNICALL
Java_atom_engine_llamacpp_LlamaNative_nativeFree(JNIEnv *, jclass, jlong handle) {
    free_session(reinterpret_cast<Session *>(handle));
}

} // extern "C"
