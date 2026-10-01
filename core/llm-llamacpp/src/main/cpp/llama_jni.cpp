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

static void call_on_token(JNIEnv* env, jobject cb, jmethodID mid, const std::string& tok) {
    jstring jtok = env->NewStringUTF(tok.c_str());
    env->CallVoidMethod(cb, mid, jtok);
    env->DeleteLocalRef(jtok);
}

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

extern "C" JNIEXPORT jlong JNICALL
Java_com_weirdo_neural_core_llm_llamacpp_LlamaBridge_nativeLoadModel(
        JNIEnv* env, jobject,
        jstring jpath, jint n_ctx, jint n_threads, jboolean use_mmap) {

    std::string path = jstring_to_std(env, jpath);
    LOGI("Carregando modelo: %s (ctx=%d, threads=%d, mmap_req=%d)",
         path.c_str(), n_ctx, n_threads, (int)use_mmap);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    // Nota: 'use_mmap' e 'use_mlock' não existem mais nesta versão.
    // O mmap é gerenciado internamente pelo loader.

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

    // CORREÇÃO: llama_sampler_init_penalties agora exige n_vocab como 1º arg
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

    while (n_generated < max_tokens) {
        if (h->cancel_flag.load()) {
            LOGI("Geração cancelada pelo usuário");
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
            std::string piece(buf, n);
            call_on_token(env, callback, mid_on_token, piece);
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
