#include <jni.h>
#include <string>
#include <vector>
#include <sstream>
#include <iomanip>
#include <cstring>
#include <chrono>
#include <android/log.h>

#include "llama.h"

#define TAG "LlamaAndroid"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// =========================
// 静态全局：模型/context 只加载一次
// =========================
static llama_model  * g_model  = nullptr;
static llama_context* g_ctx    = nullptr;
static const llama_vocab * g_vocab = nullptr;
static llama_sampler * g_sampler = nullptr;

// 对话历史（累积，多轮）。role + content
struct ChatMsg { std::string role; std::string content; };
static std::vector<ChatMsg> g_history;

// 生成位置累计计数（跨多轮）
static int32_t g_n_past = 0;

// 返回给 Java 的性能信息分隔符
static const char * PERF_BEGIN = "\n<<<PERF>>>";
static const char * PERF_END   = "<<<END>>>";

// 构造完整 ChatML（含已生成内容，用于 prefill）
static std::string build_chatml(const std::vector<ChatMsg> & msgs,
                                  const std::string & system_msg,
                                  bool add_assistant_start) {
    std::ostringstream ss;
    ss << "<|im_start|>system\n" << system_msg << "<|im_end|>\n";
    for (const auto & m : msgs) {
        ss << "<|im_start|>" << m.role << "\n" << m.content << "<|im_end|>\n";
    }
    if (add_assistant_start) {
        ss << "<|im_start|>assistant\n";
    }
    return ss.str();
}

// =========================
// loadModel
// =========================
extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_llamaandroid_MainActivity_loadModel(
        JNIEnv* env, jobject, jstring modelPath) {

    if (g_model != nullptr) {
        return env->NewStringUTF("OK: 模型已加载");
    }

    const char * path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("==== loadModel start ====");
    LOGI("model_path = %s", path);

    llama_backend_init();

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    g_model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(modelPath, path);

    if (g_model == nullptr) {
        LOGE("ERROR: 无法加载 GGUF 模型");
        return env->NewStringUTF("ERROR: 无法载 GGUF 模型");
    }

    {
        char desc[256] = {0};
        llama_model_desc(g_model, desc, sizeof(desc));
        LOGI("model_desc = %s", desc);
    }
    const char * tmpl = llama_model_chat_template(g_model, nullptr);
    LOGI("model_chat_template = %s", tmpl ? tmpl : "(null)");

    g_vocab = llama_model_get_vocab(g_model);
    LOGI("bos=%d eos=%d add_bos=%d add_eos=%d",
         (int)llama_vocab_bos(g_vocab), (int)llama_vocab_eos(g_vocab),
         (int)llama_vocab_get_add_bos(g_vocab), (int)llama_vocab_get_add_eos(g_vocab));

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = 2048;
    cp.n_batch = 2048;
    cp.n_threads = 6;
    cp.n_threads_batch = 6;
    g_ctx = llama_init_from_model(g_model, cp);
    if (g_ctx == nullptr) {
        LOGE("ERROR: llama_init_from_model 失败");
        llama_model_free(g_model);
        g_model = nullptr;
        llama_backend_free();
        return env->NewStringUTF("ERROR: 无法创建 llama context");
    }

    g_sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_dist(1234));

    // 重置状态
    g_history.clear();
    g_n_past = 0;
    llama_memory_clear(llama_get_memory(g_ctx), true);

    LOGI("==== loadModel done ====");
    return env->NewStringUTF("OK: 模型加载完成");
}

// =========================
// runChat：使用累积历史，重新构造完整 prompt，清 KV，重新 prefill，再生成
// 返回格式：answer<<<PERF>>>key=val;key=val<<<END>>>
// =========================
extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_llamaandroid_MainActivity_runChat(
        JNIEnv* env, jobject, jstring userInput) {

    if (g_model == nullptr || g_ctx == nullptr || g_vocab == nullptr || g_sampler == nullptr) {
        return env->NewStringUTF("ERROR<<<PERF>>>err=模型未加载<<<END>>>");
    }

    const char * input = env->GetStringUTFChars(userInput, nullptr);
    std::string user_str(input);
    env->ReleaseStringUTFChars(userInput, input);

    LOGI("==== runChat start, user='%s', history_turns=%d ====", user_str.c_str(), (int)g_history.size());

    // 1. 加入用户消息
    g_history.push_back({"user", user_str});

    // 2. 构造完整 ChatML（含 assistant 起点）
    std::string system_msg = "你是 Qwen，一个乐于助人的 AI 助手。请用简洁的中文回答。";
    std::string prompt = build_chatml(g_history, system_msg, /*add_assistant_start=*/true);

    // 3. Tokenize（add_special=false，parse_special=true）
    int32_t n_tokens = llama_tokenize(g_vocab,
                                      prompt.c_str(), (int32_t)prompt.size(),
                                      nullptr, 0,
                                      /*add_special=*/false, /*parse_special=*/true);
    if (n_tokens >= 0) {
        LOGE("ERROR: tokenize 取长度失败: %d", n_tokens);
        g_history.pop_back(); // 回滚
        return env->NewStringUTF("ERROR: tokenize 失败<<<PERF>>>err=tokenize_len<<<END>>>");
    }
    n_tokens = -n_tokens;

    std::vector<llama_token> tokens(n_tokens);
    int32_t actual = llama_tokenize(g_vocab,
                                    prompt.c_str(), (int32_t)prompt.size(),
                                    tokens.data(), n_tokens,
                                    /*add_special=*/false, /*parse_special=*/true);
    if (actual < 0) {
        LOGE("ERROR: tokenize 第二次失败: %d", actual);
        g_history.pop_back();
        return env->NewStringUTF("ERROR: tokenize 失败<<<PERF>>>err=tokenize_fill<<<END>>>");
    }
    n_tokens = actual;
    tokens.resize(n_tokens);
    LOGI("prefill n_tokens=%d", n_tokens);

    // 4. 检查 context 上限
    if (n_tokens >= 2000) { // 留些余量给生成
        LOGE("ERROR: 对话过长 n_tokens=%d", n_tokens);
        g_history.pop_back();
        return env->NewStringUTF("ERROR: 对话过长，请清空后重试<<<PERF>>>err=ctx_overflow<<<END>>>");
    }

    // 5. 清空 KV cache，重新 prefill 整个对话
    llama_memory_clear(llama_get_memory(g_ctx), true);
    g_n_past = 0;

    llama_batch batch = llama_batch_init(n_tokens, 0, 1);
    for (int i = 0; i < n_tokens; ++i) {
        batch.token[i] = tokens[i];
        batch.pos[i]   = i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = (i == n_tokens - 1);
    }
    batch.n_tokens = n_tokens;

    // ===== 计时开始 =====
    auto t_start = std::chrono::steady_clock::now();

    int result = llama_decode(g_ctx, batch);
    if (result != 0) {
        LOGE("ERROR: prefill decode failed result=%d", result);
        llama_batch_free(batch);
        g_history.pop_back();
        return env->NewStringUTF(("ERROR: prefill 失败 (result=" + std::to_string(result) + ")<<<PERF>>>err=prefill_decode<<<END>>>").c_str());
    }

    auto t_first_token = std::chrono::steady_clock::now();
    g_n_past = n_tokens;
    int prompt_tokens = n_tokens;

    // 6. 生成
    std::string output;
    const int max_new_tokens = 512;
    int generated = 0;

    for (int i = 0; i < max_new_tokens; ++i) {
        llama_token new_token = llama_sampler_sample(g_sampler, g_ctx, -1);
        llama_sampler_accept(g_sampler, new_token);

        if (llama_vocab_is_eog(g_vocab, new_token)) {
            LOGI("EOG hit at i=%d", i);
            break;
        }

        char piece[256] = {0};
        int n_chars = llama_token_to_piece(g_vocab, new_token,
                                           piece, sizeof(piece),
                                           0, /*special=*/true);
        if (n_chars > 0) {
            output.append(piece, n_chars);
        }
        ++generated;

        batch.n_tokens = 1;
        batch.token[0] = new_token;
        batch.pos[0]   = g_n_past + i;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = true;

        result = llama_decode(g_ctx, batch);
        if (result != 0) {
            LOGE("ERROR: gen decode failed at i=%d result=%d", i, result);
            output += "\n[ERROR] gen decode result=" + std::to_string(result);
            break;
        }
    }

    auto t_end = std::chrono::steady_clock::now();
    llama_batch_free(batch);

    // 7. 把回答加入历史
    g_history.push_back({"assistant", output});

    // ===== 计算指标 =====
    double prefill_ms = std::chrono::duration<double, std::milli>(t_first_token - t_start).count();
    double total_ms   = std::chrono::duration<double, std::milli>(t_end - t_start).count();
    double decode_ms  = std::chrono::duration<double, std::milli>(t_end - t_first_token).count();

    // TTFT（首 token 时间）≈ prefill 时间
    double ttft_s   = prefill_ms / 1000.0;
    double total_s  = total_ms / 1000.0;
    double decode_s = decode_ms / 1000.0;
    double prefill_speed = (generated > 0 && prefill_ms > 0)
                            ? (prompt_tokens / (prefill_ms / 1000.0)) : 0.0;
    double decode_speed   = (generated > 0 && decode_ms > 0)
                            ? (generated / (decode_ms / 1000.0)) : 0.0;

    LOGI("perf: prompt_tokens=%d generated=%d ttft=%.3fs prefill=%.0ftok/s decode=%.0ftok/s total=%.3fs",
         prompt_tokens, generated, ttft_s, prefill_speed, decode_speed, total_s);

    // 8. 拼接返回
    std::ostringstream ret;
    ret << output
        << PERF_BEGIN
        << "prefill_t=" << prefill_ms << "ms;"
        << "prompt_tokens=" << prompt_tokens << ";"
        << "prefill_speed=" << (int)prefill_speed << "tok/s;"
        << "ttft=" << std::fixed << std::setprecision(3) << ttft_s << "s;"
        << "decode_t=" << decode_ms << "ms;"
        << "generated=" << generated << ";"
        << "decode_speed=" << (int)decode_speed << "tok/s;"
        << "total=" << total_s << "s"
        << PERF_END;

    LOGI("==== runChat end, out_len=%d ====", (int)output.size());
    return env->NewStringUTF(ret.str().c_str());
}

// =========================
// clearChat：清空对话历史与 KV
// =========================
extern "C"
JNIEXPORT void JNICALL
Java_com_example_llamaandroid_MainActivity_clearChat(JNIEnv*, jobject) {
    LOGI("==== clearChat ====");
    g_history.clear();
    g_n_past = 0;
    if (g_ctx) {
        llama_memory_clear(llama_get_memory(g_ctx), true);
        if (g_sampler) llama_sampler_reset(g_sampler);
    }
}

// =========================
// unloadModel
// =========================
extern "C"
JNIEXPORT void JNICALL
Java_com_example_llamaandroid_MainActivity_unloadModel(JNIEnv*, jobject) {
    LOGI("==== unloadModel ====");
    g_history.clear();
    g_n_past = 0;
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_ctx)     { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model)   { llama_model_free(g_model); g_model = nullptr; }
    g_vocab = nullptr;
    llama_backend_free();
}
