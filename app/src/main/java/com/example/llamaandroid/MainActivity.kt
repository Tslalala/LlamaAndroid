package com.example.llamaandroid

import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import androidx.appcompat.app.AppCompatActivity
import com.example.llamaandroid.databinding.ActivityMainBinding

/**
 * 对话页（原 MainActivity）。
 * 由 HomeActivity 启动，Intent 携带：model_path / model_name / n_ctx / thinking。
 * 本页只负责：加载模型 → 对话 → 清空。模型管理/设置已在主页完成。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var modelReady = false
    private var generating = false

    // 从 Intent 接收的参数
    private var modelPath: String = ""
    private var modelName: String = ""
    private var nCtx: Int = 2048
    private var thinking: Boolean = false

    // 流式生成缓冲：用 StringBuilder 累积 token，UI 增量刷新
    private val streamBuf = StringBuilder()

    // 对话历史 UI 副本：独立维护，避免 substring 拼接时错位/丢失历史
    private data class ChatTurn(val role: String, val content: String)
    private val uiChatHistory = ArrayList<ChatTurn>()

    // 流式 UI 刷新节流：约 20fps（50ms 一次）
    private var lastUiFlushMs: Long = 0
    private val UI_FLUSH_INTERVAL_MS = 50L
    private var scrollPending = false

    // 流式期间是否自动滚动到底部
    private var autoScroll = true
    private var touchStartScrollY = 0

    // 当前已加载的模型名，用于状态栏显示
    private var loadedModelName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 从 Intent 读取参数
        modelPath = intent.getStringExtra(EXTRA_MODEL_PATH) ?: run {
            finish(); return
        }
        modelName = intent.getStringExtra(EXTRA_MODEL_NAME) ?: ""
        nCtx = intent.getIntExtra(EXTRA_N_CTX, 2048)
        thinking = intent.getBooleanExtra(EXTRA_THINKING, false)
        loadedModelName = modelName

        binding.sendButton.setOnClickListener { onSendClick() }
        binding.clearButton.setOnClickListener { onClearClick() }

        // 标题栏显示模型名（居中，无「已加载」字样）
        binding.titleText.text = modelName

        // 软键盘「发送」键也触发发送
        binding.inputEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                onSendClick(); true
            } else false
        }

        // ScrollView 触摸监听：用户手指上翻 -> 暂停 autoScroll
        binding.resultScroll.setOnTouchListener { _, ev ->
            if (ev.action == android.view.MotionEvent.ACTION_DOWN) {
                touchStartScrollY = binding.resultScroll.scrollY
            } else if (ev.action == android.view.MotionEvent.ACTION_MOVE) {
                if (binding.resultScroll.scrollY < touchStartScrollY - 8) {
                    autoScroll = false
                }
            } else if (ev.action == android.view.MotionEvent.ACTION_UP) {
                val sv = binding.resultScroll
                val child = sv.getChildAt(0)
                if (child != null && sv.scrollY + sv.height >= child.bottom - 24) {
                    autoScroll = true
                }
            }
            false
        }

        updateUi()
        // 自动加载模型
        loadModelFromIntent()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 离开对话页时卸载模型，释放 native 资源
        if (!generating) {
            unloadModel()
        }
    }

    // =========================
    // 加载模型（从 Intent 参数）
    // =========================
    private fun loadModelFromIntent() {
        android.util.Log.i("LlamaAndroid", "Chat loadModel: name=$modelName path=$modelPath nCtx=$nCtx thinking=$thinking")
        binding.statusText.visibility = android.view.View.VISIBLE
        binding.statusText.text = getString(R.string.status_loading)
        modelReady = false
        updateUi()

        Thread {
            unloadModel()
            val result = loadModelNative(modelPath, nCtx)
            android.util.Log.i("LlamaAndroid", "loadModelNative result='$result' nCtx=$nCtx")
            runOnUiThread {
                if (result.startsWith("OK")) {
                    modelReady = true
                    loadedModelName = modelName
                    // 成功后隐藏状态行（标题已是模型名，不再显示「已加载」字样）
                    binding.statusText.visibility = android.view.View.GONE
                    uiChatHistory.clear()
                    binding.resultText.text = getString(R.string.result_placeholder)
                    binding.perfText.text = ""
                } else {
                    binding.statusText.visibility = android.view.View.VISIBLE
                    binding.statusText.text = "ERROR: $result"
                }
                updateUi()
            }
        }.start()
    }

    // =========================
    // 聊天
    // =========================
    private fun onSendClick() {
        android.util.Log.i("LlamaAndroid", "onSendClick: modelReady=$modelReady generating=$generating")
        if (!modelReady || generating) return
        val question = binding.inputEdit.text.toString().trim()
        if (question.isEmpty()) return

        generating = true
        autoScroll = true
        binding.inputEdit.setText("")
        updateUi()

        uiChatHistory.add(ChatTurn("User", question))
        uiChatHistory.add(ChatTurn("Assistant", ""))
        rebuildChatText(forceScroll = true)

        streamBuf.setLength(0)

        Thread {
            try {
                val raw = runChat(question, thinking)
                val (answer, perf) = parseResult(raw)
                runOnUiThread {
                    val last = uiChatHistory.lastOrNull()
                    if (last != null && last.role == "Assistant") {
                        uiChatHistory[uiChatHistory.size - 1] = ChatTurn("Assistant", answer)
                    }
                    uiChatHistory.add(ChatTurn("Perf", perf))
                    rebuildChatText(forceScroll = true)
                    // 成功后隐藏状态行（标题已是模型名）
                    if (loadedModelName.isNotEmpty()) {
                        binding.statusText.visibility = android.view.View.GONE
                    }
                    binding.perfText.text = ""
                }
            } catch (e: Exception) {
                runOnUiThread {
                    val last = uiChatHistory.lastOrNull()
                    if (last != null && last.role == "Assistant") {
                        uiChatHistory[uiChatHistory.size - 1] = ChatTurn("Assistant", "[ERROR] ${e.message}")
                    }
                    rebuildChatText(forceScroll = true)
                    binding.statusText.visibility = android.view.View.VISIBLE
                    binding.statusText.text = "ERROR: ${e.message}"
                }
            } finally {
                generating = false
                runOnUiThread { updateUi() }
            }
        }.start()
    }

    /**
     * JNI 流式回调：native 每生成一个 token 会调用此方法。
     * 节流 50ms；流式期间非强制不滚，用户可自由上翻。
     */
    fun onTokenGenerated(piece: String) {
        streamBuf.append(piece)
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastUiFlushMs < UI_FLUSH_INTERVAL_MS) return
        lastUiFlushMs = now
        val text = streamBuf.toString()
        runOnUiThread {
            val last = uiChatHistory.lastOrNull()
            if (last != null && last.role == "Assistant") {
                uiChatHistory[uiChatHistory.size - 1] = ChatTurn("Assistant", text)
                rebuildChatText(forceScroll = false)
            }
        }
    }

    private fun onClearClick() {
        clearChat()
        uiChatHistory.clear()
        binding.resultText.text = getString(R.string.result_placeholder)
        binding.perfText.text = ""
        // 清空对话后隐藏状态行（标题已是模型名）
        if (modelReady && loadedModelName.isNotEmpty()) {
            binding.statusText.visibility = android.view.View.GONE
        } else {
            binding.statusText.visibility = android.view.View.VISIBLE
            binding.statusText.text = getString(R.string.chat_disabled)
        }
    }

    /**
     * 解析 native 返回：answer<<<PERF>>>k=v;k=v<<<END>>>
     */
    private fun parseResult(raw: String): Pair<String, String> {
        val begin = raw.indexOf("<<<PERF>>>")
        val end   = raw.indexOf("<<<END>>>")
        if (begin < 0 || end < 0 || end <= begin) {
            return Pair(raw, "（无性能数据）")
        }
        val answer = raw.substring(0, begin)
        val perfRaw = raw.substring(begin + "<<<PERF>>>".length, end)
        val parts = perfRaw.split(";").filter { it.contains("=") }
        val sb = StringBuilder()
        for (p in parts) {
            val kv = p.split("=", limit = 2)
            if (kv.size == 2) {
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append(kv[0]).append("=").append(kv[1])
            }
        }
        return Pair(answer, sb.toString())
    }

    /**
     * 从 uiChatHistory 列表重建整个对话 TextView。
     * User 蓝色加粗、Assistant 绿色加粗、Perf 灰色 3 行。
     *
     * 注意：textIsSelectable 的 TextView 在 setText 时会重置 selection 到 offset 0，
     * 触发 bringPointIntoView(0) 导致 ScrollView 强制跳到顶部。
     * 流式期间需保存/恢复 scrollY 防止跳顶。
     */
    private fun rebuildChatText(forceScroll: Boolean = false) {
        val savedY = binding.resultScroll.scrollY
        val ssb = SpannableStringBuilder()
        for (turn in uiChatHistory) {
            if (ssb.isNotEmpty()) ssb.append("\n\n")
            when (turn.role) {
                "Perf" -> {
                    val block = formatPerf(turn.content)
                    val start = ssb.length
                    ssb.append(block)
                    ssb.setSpan(ForegroundColorSpan(0xFF888888.toInt()),
                        start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                else -> {
                    val roleStart = ssb.length
                    ssb.append(turn.role).append(": ")
                    val roleColor = if (turn.role == "User") 0xFF1565C0.toInt()
                                    else 0xFF2E7D32.toInt()
                    ssb.setSpan(ForegroundColorSpan(roleColor),
                        roleStart, roleStart + turn.role.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ssb.setSpan(StyleSpan(Typeface.BOLD),
                        roleStart, roleStart + turn.role.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ssb.append(turn.content)
                }
            }
        }
        binding.resultText.text = ssb
        if (forceScroll) {
            scrollToEnd(true)
        } else if (generating) {
            // 流式期间 setText 可能触发 selectable TextView 跳顶，恢复用户浏览位置
            binding.resultScroll.post {
                if (generating) binding.resultScroll.scrollTo(0, savedY)
            }
        }
    }

    /** 把 perf 的 k=v 文本格式化为 3 行展示 */
    private fun formatPerf(perf: String): String {
        val map = HashMap<String, String>()
        for (line in perf.split("\n")) {
            val kv = line.split("=", limit = 2)
            if (kv.size == 2) map[kv[0].trim()] = kv[1].trim()
        }
        fun numS(key: String): String {
            val v = map[key] ?: return "?"
            val d = v.replace("ms", "").replace("s", "").replace("tok/s", "")
                        .replace("tokens/s", "").trim().toDoubleOrNull() ?: return "?"
            return if (v.contains("ms")) String.format("%.2f", d / 1000.0)
                   else String.format("%.2f", d)
        }
        fun num2(key: String): String {
            val v = map[key] ?: return "?"
            val d = v.replace("tok/s", "").replace("tokens/s", "").trim().toDoubleOrNull() ?: return "?"
            return String.format("%.2f", d)
        }
        fun intStr(key: String): String = map[key]?.replace(Regex("[^0-9]"), "") ?: "?"
        val prefillT  = numS("prefill_t")
        val promptTok = intStr("prompt_tokens")
        val genTok    = intStr("generated")
        val decodeT   = numS("decode_t")
        val decodeSpd = num2("decode_speed")
        val totalT    = numS("total")
        return buildString {
            append("prefill_time: ").append(prefillT).append("s; ")
            append("prompt_tok: ").append(promptTok).append("\n")
            append("generat_tok:  ").append(genTok).append("; ")
            append("decode_time: ").append(decodeT).append("s\n")
            append("decode_spd: ").append(decodeSpd).append(" tok/s; ")
            append("total: ").append(totalT).append("s")
        }
    }

    private fun scrollToEnd(force: Boolean = false) {
        if (generating && !force) return
        if (scrollPending) return
        scrollPending = true
        binding.resultScroll.post {
            scrollPending = false
            val sv = binding.resultScroll
            val child = sv.getChildAt(0) ?: return@post
            val targetY = (child.bottom - sv.height).coerceAtLeast(0)
            sv.scrollTo(0, targetY)
        }
    }

    private fun updateUi() {
        val canSend = modelReady && !generating
        binding.sendButton.isEnabled = canSend
        binding.clearButton.isEnabled = modelReady && !generating
        binding.sendButton.text = if (generating) getString(R.string.status_generating)
                                  else getString(R.string.send)
        binding.inputEdit.isEnabled = canSend
        if (!modelReady && !generating) {
            binding.statusText.visibility = android.view.View.VISIBLE
            binding.statusText.text = getString(R.string.chat_disabled)
        }
    }

    // ===== JNI =====
    private external fun loadModelNative(modelPath: String, nCtx: Int): String
    private external fun runChat(userInput: String, thinking: Boolean): String
    private external fun clearChat()
    private external fun unloadModel()

    companion object {
        const val EXTRA_MODEL_PATH = "model_path"
        const val EXTRA_MODEL_NAME  = "model_name"
        const val EXTRA_N_CTX       = "n_ctx"
        const val EXTRA_THINKING    = "thinking"

        init {
            System.loadLibrary("llamaandroid")
        }
    }
}
