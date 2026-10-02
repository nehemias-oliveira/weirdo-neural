#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "WeirdoLlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct LlamaHandle {
    llama_model*   model = nullptr;
    llama_context* ctx   = nullptr;
    const llama_vocab* vocab = nullptr;
    std::atomic<bool> cancel_flag{false};
    std::mutex gen_mutex;
};

static bool g_backend_initialized = false;

static std::string jstring_to_std(JNIEnv* env, jstring js) {
    if (!js) return {};
    const char* c = env->GetStringUTFChars(js, nullptr);
    std::string s(c);
    env->ReleaseStringUTFChars(js, c);
    return s;
}

// ---------------------------------------------------------------------------
// UTF-8 → UTF-16 (com tratamento de sequências multi-byte e surrogates)
// Retorna número de bytes consumidos. Append do UTF-16 em `out`.
// ---------------------------------------------------------------------------
static size_t utf8_decode(const std::string& s, std::u16string& out) {
    out.clear();
    size_t i = 0;
    while (i < s.size()) {
        unsigned char c = (unsigned char)s[i];
        uint32_t cp;
        size_t n;

        if (c < 0x80) { cp = c; n = 1; }
        else if ((c & 0xE0) == 0xC0) { cp = c & 0x1F; n = 2; }
        else if ((c & 0xF0) == 0xE0) { cp = c & 0x0F; n = 3; }
        else if ((c & 0xF8) == 0xF0) { cp = c & 0x07; n = 4; }
        else break; // lead byte inválido

        if (i + n > s.size()) break; // sequência incompleta — espera mais bytes

        bool ok = true;
        for (size_t j = 1; j < n; j++) {
            if (((unsigned char)s[i + j] & 0xC0) != 0x80) { ok = false; break; }
            cp = (cp << 6) | ((unsigned char)s[i + j] & 0x3F);
        }
        if (!ok) break;

        i += n;

        if (cp < 0x10000) {
            out.push_back((char16_t)cp);
        } else if (cp <= 0x10FFFF) {
            cp -= 0x10000;
            out.push_back((char16_t)(0xD800 + (cp >> 10)));
            out.push_back((char16_t)(0xDC00 + (cp & 0x3FF)));
        }
    }
    return i;
}

// Callback que chama onToken(String) no Kotlin, aceitando UTF-16.
static void call_on_token_utf16(JNIEnv* env, jobject cb, jmethodID mid,
                                 const std::u16string& utf16) {
    if (utf16.empty()) return;
    jstring jtok = env->NewString((const jchar*)utf16.data(), utf16.size());
    env->CallVoidMethod(cb, mid, jtok);
    env->DeleteLocalRef(jtok);
}

// ---------------------------------------------------------------------------
// Backend
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT void JNICALL
Java_com_weirdo_neural_core_llm_llamacpp_LlamaBridge_nativeBackendInit(
        JNIEnv*, jobject) {
    if (g_backend_initialized) return;
    llama_backend_init();
    g_backend_initialized = true;
    LOGI("Backend inicializado");
}

extern "C" JNIEXPORT void JNICALL
Java_com_weirdo_neural_core_llm_llamacpp_LlamaBridge_nativeBackendFree(
        JNIEnv*, jobject) {
    if (!g_backend_initialized) return;
    llama_backend_free();
    g_backend_initialized = false;
    LOGI("Backend liberado");
}

// ---------------------------------------------------------------------------
// Load / Free
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jlong JNICALL
Java_com_weirdo_neural_core_llm_llamacpp_LlamaBridge_nativeLoadModel(
        JNIEnv* env, jobject,
        jstring jpath, jint n_ctx, jint n_threads, jboolean use_mmap) {

    std::string path = jstring_to_std(env, jpath);
    LOGI("Carregando modelo: %s (ctx=%d, threads=%d)",
         path.c_str(), n_ctx, n_threads);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;

    llama_model* model = llama_model_load_from_file(path.c_str(), mparams);
    if (!model) {
        LOGE("Falha ao carregar modelo");
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx     = n_ctx;
    cparams.n_threads = n_threads;
    cparams.n_batch   = 512;
    cparams.n_ubatch  = 512;

    llama_context* ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGE("Falha ao criar contexto");
        llama_model_free(model);
        return 0;
    }

    auto* h = new LlamaHandle();
    h->model = model;
    h->ctx   = ctx;
    h->vocab = llama_model_get_vocab(model);

    LOGI("Modelo carregado. n_ctx efetivo = %u", llama_n_ctx(ctx));
    return reinterpret_cast<jlong>(h);
}

extern "C" JNIEXPORT void JNICALL
Java_com_weirdo_neural_core_llm_llamacpp_LlamaBridge_nativeFreeModel(
        JNIEnv*, jobject, jlong handle) {
    auto* h = reinterpret_cast<LlamaHandle*>(handle);
    if (!h) return;
    if (h->ctx)   llama_free(h->ctx);
    if (h->model) llama_model_free(h->model);
    delete h;
    LOGI("Modelo liberado");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_weirdo_neural_core_llm_llamacpp_LlamaBridge_nativeGetChatTemplate(
        JNIEnv* env, jobject, jlong handle) {
    auto* h = reinterpret_cast<LlamaHandle*>(handle);
    if (!h || !h->model) return nullptr;
    const char* tmpl = llama_model_chat_template(h->model, nullptr);
    if (!tmpl) return nullptr;
    return env->NewStringUTF(tmpl);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_weirdo_neural_core_llm_llamacpp_LlamaBridge_nativeCountTokens(
        JNIEnv* env, jobject, jlong handle, jstring jtext) {
    auto* h = reinterpret_cast<LlamaHandle*>(handle);
    if (!h || !h->vocab) return -1;
    std::string text = jstring_to_std(env, jtext);
    int n = -llama_tokenize(h->vocab, text.c_str(), text.size(),
                            nullptr, 0, true, true);
    return n;
}

// ---------------------------------------------------------------------------
// Generate
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT void JNICALL
Java_com_weirdo_neural_core_llm_llamacpp_LlamaBridge_nativeGenerate(
        JNIEnv* env, jobject, jlong handle, jstring jprompt,
        jfloat temperature, jint top_k, jfloat top_p, jfloat min_p,
        jfloat repeat_penalty, jint repeat_last_n, jint max_tokens, jint seed,
        jobject callback) {

    auto* h = reinterpret_cast<LlamaHandle*>(handle);
    if (!h || !h->ctx) return;

    jclass cb_class = env->GetObjectClass(callback);
    jmethodID mid_on_token = env->GetMethodID(cb_class, "onToken", "(Ljava/lang/String;)V");
    jmethodID mid_on_done  = env->GetMethodID(cb_class, "onDone",  "()V");
    jmethodID mid_on_error = env->GetMethodID(cb_class, "onError", "(Ljava/lang/String;)V");

    if (!mid_on_token || !mid_on_done || !mid_on_error) {
        LOGE("Métodos de callback não encontrados");
        return;
    }

    h->cancel_flag.store(false);
    std::lock_guard<std::mutex> lock(h->gen_mutex);

    std::string prompt = jstring_to_std(env, jprompt);

    int n_prompt = -llama_tokenize(h->vocab, prompt.c_str(), prompt.size(),
                                   nullptr, 0, true, true);
    if (n_prompt <= 0) {
        env->CallVoidMethod(callback, mid_on_error,
                            env->NewStringUTF("Falha ao tokenizar prompt"));
        return;
    }

    std::vector<llama_token> tokens(n_prompt);
    llama_tokenize(h->vocab, prompt.c_str(), prompt.size(),
                   tokens.data(), tokens.size(), true, true);

    llama_memory_clear(llama_get_memory(h->ctx), true);

    llama_batch batch = llama_batch_get_one(tokens.data(), tokens.size());
    if (llama_decode(h->ctx, batch) != 0) {
        env->CallVoidMethod(callback, mid_on_error,
                            env->NewStringUTF("Falha no decode do prompt"));
        return;
    }

    auto sparams = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sparams);

    const int32_t n_vocab = llama_vocab_n_tokens(h->vocab);
    if (repeat_last_n > 0 && repeat_penalty != 1.0f) {
        llama_sampler_chain_add(smpl,
            llama_sampler_init_penalties(n_vocab, repeat_last_n, repeat_penalty, 0.0f, 0.0f));
    }
    if (top_k > 0) {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(top_k));
    }
    if (top_p < 1.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p, 1));
    }
    if (min_p > 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_min_p(min_p, 1));
    }
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(
        seed >= 0 ? (uint32_t)seed : LLAMA_DEFAULT_SEED));

    int n_generated = 0;
    std::string pending_utf8; // buffer de bytes UTF-8 incompletos

    while (n_generated < max_tokens) {
        if (h->cancel_flag.load()) {
            LOGI("Geração cancelada");
            break;
        }

        llama_token new_token = llama_sampler_sample(smpl, h->ctx, -1);

        if (llama_vocab_is_eog(h->vocab, new_token)) {
            LOGI("EOS atingido");
            break;
        }

        llama_sampler_accept(smpl, new_token);

        char buf[256];
        int n = llama_token_to_piece(h->vocab, new_token, buf, sizeof(buf), 0, true);
        if (n > 0) {
            pending_utf8.append(buf, n);

            std::u16string utf16;
            size_t consumed = utf8_decode(pending_utf8, utf16);

            if (consumed > 0) {
                pending_utf8.erase(0, consumed);
                call_on_token_utf16(env, callback, mid_on_token, utf16);
            } else if (!pending_utf8.empty()) {
                // Se o primeiro byte é uma continuação órfã ou inválido, descarta
                unsigned char c = (unsigned char)pending_utf8[0];
                bool valid_lead = (c < 0x80) || ((c >= 0xC0) && (c <= 0xF7));
                if (!valid_lead) {
                    pending_utf8.erase(0, 1);
                }
                // Se é lead válido, mantém e espera o próximo token
            }
        }

        batch = llama_batch_get_one(&new_token, 1);
        if (llama_decode(h->ctx, batch) != 0) {
            env->CallVoidMethod(callback, mid_on_error,
                                env->NewStringUTF("Falha no decode"));
            llama_sampler_free(smpl);
            return;
        }

        n_generated++;
    }

    // Flush final: se sobrou algo que decodifica, emite
    if (!pending_utf8.empty()) {
        std::u16string utf16;
        size_t consumed = utf8_decode(pending_utf8, utf16);
        if (consumed > 0) {
            call_on_token_utf16(env, callback, mid_on_token, utf16);
        }
    }

    llama_sampler_free(smpl);
    env->CallVoidMethod(callback, mid_on_done);
    LOGI("Geração concluída: %d tokens", n_generated);
}

extern "C" JNIEXPORT void JNICALL
Java_com_weirdo_neural_core_llm_llamacpp_LlamaBridge_nativeCancel(
        JNIEnv*, jobject, jlong handle) {
    auto* h = reinterpret_cast<LlamaHandle*>(handle);
    if (!h) return;
    h->cancel_flag.store(true);
}
