#include <jni.h>
#include <string>
#include <vector>
#include <sstream>
#include <cstring>
#include <android/log.h>

#include "llama.h"

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_llamaandroid_MainActivity_runLlama(
        JNIEnv* env,
        jobject /* this */,
        jstring modelPath) {

    const char * model_path =
            env->GetStringUTFChars(
                    modelPath,
                    nullptr);

    const char * prompt =
            "Hi, introduce yourself";

    // 初始化 llama 后端
    llama_backend_init();

    // =========================
    // 1. 加载模型
    // =========================

    llama_model_params model_params =
            llama_model_default_params();

    // 手机内存有限，先使用 CPU
    model_params.n_gpu_layers = 0;

    llama_model * model =
            llama_model_load_from_file(
                    model_path,
                    model_params);

    if (model == nullptr) {
        llama_backend_free();

        return env->NewStringUTF(
                "ERROR: 无法加载 GGUF 模型");
    }

    // =========================
    // 2. 获取 vocabulary
    // =========================

    const llama_vocab * vocab =
            llama_model_get_vocab(model);

    // =========================
    // 3. Tokenize
    // =========================

    int32_t n_tokens =
            llama_tokenize(
                    vocab,
                    prompt,
                    static_cast<int32_t>(strlen(prompt)),
                    nullptr,
                    0,
                    true,
                    true);

// llama_tokenize 在 buffer 不够时返回负数，绝对值就是需要的 token 数
    if (n_tokens >= 0) {
        llama_model_free(model);
        llama_backend_free();

        return env->NewStringUTF(
                "ERROR: tokenize 获取 token 数量失败");
    }

    n_tokens = -n_tokens;

    std::vector<llama_token> tokens(n_tokens);

    int32_t actual_tokens =
            llama_tokenize(
                    vocab,
                    prompt,
                    static_cast<int32_t>(strlen(prompt)),
                    tokens.data(),
                    n_tokens,
                    true,
                    true);

    if (actual_tokens < 0) {
        llama_model_free(model);
        llama_backend_free();

        return env->NewStringUTF(
                "ERROR: tokenize 第二次调用失败");
    }

    n_tokens = actual_tokens;
    tokens.resize(n_tokens);

    // =========================
    // 4. 创建 context
    // =========================

    llama_context_params ctx_params =
            llama_context_default_params();

    ctx_params.n_ctx = 512;
    ctx_params.n_batch = 512;

    // Magic8 CPU
    ctx_params.n_threads = 6;
    ctx_params.n_threads_batch = 6;

    llama_context * ctx =
            llama_init_from_model(
                    model,
                    ctx_params);

    if (ctx == nullptr) {
        llama_model_free(model);
        llama_backend_free();

        return env->NewStringUTF(
                "ERROR: 无法创建 llama context");
    }

    // =========================
    // 5. 创建 sampler
    // =========================

    llama_sampler_chain_params sampler_params =
            llama_sampler_chain_default_params();

    llama_sampler * sampler =
            llama_sampler_chain_init(
                    sampler_params);

    llama_sampler_chain_add(
            sampler,
            llama_sampler_init_top_k(40));

    llama_sampler_chain_add(
            sampler,
            llama_sampler_init_top_p(0.9f, 1));

    llama_sampler_chain_add(
            sampler,
            llama_sampler_init_temp(0.7f));

    llama_sampler_chain_add(
            sampler,
            llama_sampler_init_dist(1234));

    // =========================
    // 6. Decode prompt
    // =========================

    llama_batch batch =
            llama_batch_init(
                    n_tokens,
                    0,
                    1);

    for (int i = 0; i < n_tokens; ++i) {
        batch.token[i] = tokens[i];
        batch.pos[i] = i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] =
                (i == n_tokens - 1);
    }

    batch.n_tokens = n_tokens;

    // 测试
    __android_log_print(ANDROID_LOG_INFO, "LlamaAndroid", "n_tokens = %d", n_tokens);
    __android_log_print(ANDROID_LOG_INFO, "LlamaAndroid", "batch.n_tokens = %d", batch.n_tokens);

    int result = llama_decode(ctx, batch);

    if (result != 0) {
        std::string error =
                "ERROR: prompt llama_decode failed, result = "
                + std::to_string(result);

        llama_batch_free(batch);
        llama_sampler_free(sampler);
        llama_free(ctx);
        llama_model_free(model);
        llama_backend_free();

        return env->NewStringUTF(error.c_str());
    }

    // =========================
// 7. 生成文本
// =========================

    std::string output;

    const int max_new_tokens = 64;

    for (int i = 0; i < max_new_tokens; ++i) {

        // 从 sampler 获取下一个 token
        llama_token new_token =
                llama_sampler_sample(
                        sampler,
                        ctx,
                        -1);

        // 让 sampler 记录这个 token
        llama_sampler_accept(
                sampler,
                new_token);

        // EOS
        if (llama_vocab_is_eog(
                vocab,
                new_token)) {
            break;
        }

        // token -> string
        char piece[256];

        int n_chars =
                llama_token_to_piece(
                        vocab,
                        new_token,
                        piece,
                        sizeof(piece),
                        0,
                        true);

        if (n_chars > 0) {
            output.append(piece, n_chars);
        }

        // =========================
        // 准备下一次 llama_decode
        // =========================

        // 非常重要：
        // 这里只送入 1 个 token
        batch.n_tokens = 1;

        batch.token[0] = new_token;

        // prompt 有 n_tokens 个 token
        // 第一个生成 token 的位置就是 n_tokens
        batch.pos[0] = n_tokens + i;

        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;

        // 要求计算这个 token 对应位置的 logits
        batch.logits[0] = true;

        result =
                llama_decode(
                        ctx,
                        batch);

        if (result != 0) {

            output +=
                    "\n[ERROR] llama_decode failed, result = "
                    + std::to_string(result);

            break;
        }
    }

    // =========================
    // 8. 清理
    // =========================

    llama_batch_free(batch);

    llama_sampler_free(sampler);

    llama_free(ctx);

    llama_model_free(model);

    llama_backend_free();

    return env->NewStringUTF(
            output.c_str());
}