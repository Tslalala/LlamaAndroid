package com.example.llamaandroid

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.content.res.ColorStateList
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.example.llamaandroid.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var modelManager: ModelManager

    private var modelReady = false
    private var generating = false
    private var scannedModels: List<ModelManager.ModelInfo> = emptyList()

    // 流式生成缓冲：用 StringBuilder 累积 token，UI 增量刷新
    private val streamBuf = StringBuilder()

    // 对话历史 UI 副本：独立维护，避免 substring 拼接时错位/丢失历史
    // 每条是 (role, content)，渲染时从列表重建整个 TextView
    private data class ChatTurn(val role: String, val content: String)
    private val uiChatHistory = ArrayList<ChatTurn>()

    // 流式 UI 刷新节流：避免每个 token 都 post 一次刷新，导致主线程消息队列堆积
    // 约 20fps（50ms 一次），肉眼流畅且不抖动
    private var lastUiFlushMs: Long = 0
    private val UI_FLUSH_INTERVAL_MS = 50L
    // 防止 scrollToEnd 的 post 任务堆积：同一帧只排一个
    private var scrollPending = false

    // 流式期间是否自动滚动到底部：用户手动上翻后暂停 auto-scroll，拉回底部后恢复
    private var autoScroll = true
    // 触摸上翻检测：记录按下时的 scrollY
    private var touchStartScrollY = 0

    // 当前已加载的模型名，用于状态栏显示（而非"模型就绪"这种泛文案）
    private var loadedModelName: String = ""

    // 最大上下文可选项（与 native clamp 范围一致）
    private val ctxOptions = intArrayOf(512, 1024, 2048, 4096)

    // SAF 选择器：用户选择 .gguf 文件
    private val openDocLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) importModel(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        modelManager = ModelManager(this)

        binding.importButton.setOnClickListener { onImportClick() }
        binding.scanButton.setOnClickListener { refreshModelList() }
        binding.sendButton.setOnClickListener { onSendClick() }
        binding.clearButton.setOnClickListener { onClearClick() }

        // 设置面板：开/关抽屉
        binding.settingsButton.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.END)
        }
        binding.settingsCloseButton.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.END)
        }

        // 最大上下文下拉
        val ctxLabels = ctxOptions.map { "${it}" }
        binding.ctxSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, ctxLabels
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        // 默认选 2048（index=2）
        binding.ctxSpinner.setSelection(2)

        // 思考模式开关：默认关闭。注：当前 llama.cpp API 不支持运行时切模板，
        // native 通过 /no_think 反指示实现关闭；开关开启时模型按默认行为（可能输出思考块）
        binding.thinkingSwitch.setOnCheckedChangeListener { _, checked ->
            android.util.Log.i("LlamaAndroid", "thinking switch -> $checked")
        }
        binding.thinkingSwitch.isChecked = false

        // 软键盘「发送」键也触发发送
        binding.inputEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                onSendClick(); true
            } else false
        }

        // ScrollView 触摸监听：用户手指上翻 -> 立即暂停 autoScroll
        // 用触摸事件而非 scrollChanged，避免与程序 scroll 的时序竞争（用户抢不过 50ms 节流）
        binding.resultScroll.setOnTouchListener { v, ev ->
            if (ev.action == android.view.MotionEvent.ACTION_DOWN) {
                // 按下时记录起始 scrollY，判断上翻还是下翻
                touchStartScrollY = binding.resultScroll.scrollY
            } else if (ev.action == android.view.MotionEvent.ACTION_MOVE) {
                // 手指上滑（scrollY 减小）= 想看历史 -> 暂停 autoScroll
                if (binding.resultScroll.scrollY < touchStartScrollY - 8) {
                    autoScroll = false
                }
            } else if (ev.action == android.view.MotionEvent.ACTION_UP) {
                // 抬手时若已滑回底部 -> 恢复 autoScroll
                val sv = binding.resultScroll
                val child = sv.getChildAt(0)
                if (child != null && sv.scrollY + sv.height >= child.bottom - 24) {
                    autoScroll = true
                }
            }
            false // 不消费事件，让 ScrollView 正常滚动
        }

        updateUi()
        refreshModelList()
    }

    // =========================
    // 模型管理
    // =========================

    private fun refreshModelList() {
        scannedModels = modelManager.scan()
        android.util.Log.i("LlamaAndroid", "refreshModelList: found ${scannedModels.size} models")
        scannedModels.forEach { m ->
            android.util.Log.i("LlamaAndroid", "  model: ${m.name} (${m.sizeText}, ${m.source}) path=${m.path}")
        }

        val container = binding.modelListContainer
        while (container.childCount > 1) {
            container.removeViewAt(1)
        }

        if (scannedModels.isEmpty()) {
            binding.modelListText.text = getString(R.string.model_none)
            binding.modelListText.visibility = android.view.View.VISIBLE
        } else {
            binding.modelListText.visibility = android.view.View.GONE
            scannedModels.forEach { m ->
                val btn = android.widget.Button(this).apply {
                    text = "加载: ${m.name}  (${m.sizeText}, ${m.source})"
                    isAllCaps = false
                    textSize = 12f
                    minWidth = 0
                    minHeight = 0
                    minimumHeight = 0
                    setPadding(16, 8, 16, 8)
                    backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFEEEEEE.toInt())
                    setTextColor(0xFF333333.toInt())
                    setOnClickListener {
                        android.util.Log.i("LlamaAndroid", "modelButton click: ${m.name}")
                        loadModel(m)
                    }
                }
                container.addView(btn)
            }
        }
    }

    private fun onImportClick() {
        android.util.Log.i("LlamaAndroid", "onImportClick")
        openDocLauncher.launch(arrayOf("*/*"))
    }

    private fun importModel(uri: Uri) {
        val name = queryDisplayName(uri) ?: "imported_${System.currentTimeMillis()}.gguf"
        if (!name.endsWith(".gguf", ignoreCase = true)) {
            toast("请选择 .gguf 文件"); return
        }
        binding.statusText.text = getString(R.string.status_copying)

        Thread {
            val target = modelManager.importFromUri(uri, name)
            runOnUiThread {
                if (target != null) {
                    toast("导入成功: ${target.name}")
                    refreshModelList()
                    binding.statusText.text = "已导入: ${target.name}"
                } else {
                    binding.statusText.text = "ERROR: 导入失败"
                }
            }
        }.start()
    }

    private fun queryDisplayName(uri: Uri): String? {
        var name: String? = null
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0)
        }
        return name
    }

    private fun loadModel(info: ModelManager.ModelInfo) {
        android.util.Log.i("LlamaAndroid", "loadModel: name=${info.name} path=${info.path} generating=$generating")
        if (generating) { toast("正在生成，请稍候"); return }
        binding.statusText.text = getString(R.string.status_loading)
        modelReady = false
        updateUi()

        val nCtx = ctxOptions[binding.ctxSpinner.selectedItemPosition]
        Thread {
            unloadModel()
            val result = loadModelNative(info.path, nCtx)
            android.util.Log.i("LlamaAndroid", "loadModelNative result='$result' path='${info.path}' nCtx=$nCtx")
            runOnUiThread {
                if (result.startsWith("OK")) {
                    modelReady = true
                    loadedModelName = info.name
                    binding.statusText.text = getString(R.string.model_loaded, info.name)
                    uiChatHistory.clear()
                    binding.resultText.text = getString(R.string.result_placeholder)
                    binding.perfText.text = ""
                } else {
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
        android.util.Log.i("LlamaAndroid", "onSendClick: modelReady=$modelReady generating=$generating inputEnabled=${binding.inputEdit.isEnabled}")
        if (!modelReady || generating) return
        val question = binding.inputEdit.text.toString().trim()
        android.util.Log.i("LlamaAndroid", "onSendClick: question='$question'")
        if (question.isEmpty()) return

        generating = true
        // 每次新发送重置 autoScroll：保证新问答开始时自动滚到新内容
        autoScroll = true
        binding.inputEdit.setText("")
        updateUi()

        // 记录本轮对话到 UI 历史列表（独立于 native g_history）
        uiChatHistory.add(ChatTurn("User", question))
        uiChatHistory.add(ChatTurn("Assistant", ""))
        rebuildChatText(forceScroll = true)

        streamBuf.setLength(0)
        val thinking = binding.thinkingSwitch.isChecked

        Thread {
            try {
                val raw = runChat(question, thinking)
                val (answer, perf) = parseResult(raw)
                runOnUiThread {
                    // 更新最后一条 Assistant 内容为最终答案（兜底）
                    val last = uiChatHistory.lastOrNull()
                    if (last != null && last.role == "Assistant") {
                        uiChatHistory[uiChatHistory.size - 1] = ChatTurn("Assistant", answer)
                    }
                    // 追加 perf 作为独立条目（灰色显示）
                    uiChatHistory.add(ChatTurn("Perf", perf))
                    rebuildChatText(forceScroll = true)
                    // 状态栏保留模型名
                    if (loadedModelName.isNotEmpty()) {
                        binding.statusText.text = getString(R.string.model_loaded, loadedModelName)
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
     * 注意：此方法由 native 线程回调，必须切换到主线程更新 UI。
     * 节流：每 50ms 最多刷新一次，避免每个 token 都 post 导致消息队列堆积+抖动。
     * 末尾兜底：runChat 返回后用最终 answer 覆盖，保证不丢内容。
     */
    fun onTokenGenerated(piece: String) {
        streamBuf.append(piece)
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastUiFlushMs < UI_FLUSH_INTERVAL_MS) return
        lastUiFlushMs = now
        // 更新最后一条 Assistant 的 content 为当前 streamBuf
        val text = streamBuf.toString()
        runOnUiThread {
            val last = uiChatHistory.lastOrNull()
            if (last != null && last.role == "Assistant") {
                uiChatHistory[uiChatHistory.size - 1] = ChatTurn("Assistant", text)
                rebuildChatText(forceScroll = false)  // 流式期间不强制滚，用户可上翻
            }
        }
    }

    private fun onClearClick() {
        clearChat()
        uiChatHistory.clear()
        binding.resultText.text = getString(R.string.result_placeholder)
        binding.perfText.text = ""
        binding.statusText.text = if (modelReady && loadedModelName.isNotEmpty())
            getString(R.string.model_loaded, loadedModelName)
        else getString(R.string.chat_disabled)
    }

    /**
     * 解析 native 返回：answer<<<PERF>>>k=v;k=v<<<END>>>
     * 性能指标改为换行多行格式（不再用 | 拼一行），避免 perfText 折叠显示 ...
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
     * 历史完整保留在列表里，渲染时整体重建，绝不存在 substring 错位/丢失问题。
     * - User 蓝色加粗、Assistant 绿色加粗
     * - Perf 条目灰色小字，3 行格式化
     */
    private fun rebuildChatText(forceScroll: Boolean = false) {
        val ssb = SpannableStringBuilder()
        for (turn in uiChatHistory) {
            if (ssb.isNotEmpty()) ssb.append("\n\n")
            when (turn.role) {
                "Perf" -> {
                    // 灰色 perf 块，格式化 3 行
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
        scrollToEnd(forceScroll)
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
        // 核心：流式期间（generating=true）非 force 一律不自动滚，
        //   把滚动控制权完全交给用户。发送瞬间和生成结束用 force=true 滚。
        //   这样用户在流式期间可自由上翻查看历史，不再被程序抢占。
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
        android.util.Log.i("LlamaAndroid", "updateUi: modelReady=$modelReady generating=$generating canSend=$canSend")
        binding.sendButton.isEnabled = canSend
        binding.clearButton.isEnabled = modelReady && !generating
        binding.sendButton.text = if (generating) getString(R.string.status_generating)
                                  else getString(R.string.send)
        binding.inputEdit.isEnabled = canSend
        if (!modelReady && !generating) {
            binding.statusText.text = getString(R.string.chat_disabled)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ===== JNI =====
    private external fun loadModelNative(modelPath: String, nCtx: Int): String
    private external fun runChat(userInput: String, thinking: Boolean): String
    private external fun clearChat()
    private external fun unloadModel()

    companion object {
        init {
            System.loadLibrary("llamaandroid")
        }
    }
}
