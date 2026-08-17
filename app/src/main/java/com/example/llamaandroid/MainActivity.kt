package com.example.llamaandroid

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.llamaandroid.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var modelManager: ModelManager

    private var modelReady = false
    private var generating = false
    private var scannedModels: List<ModelManager.ModelInfo> = emptyList()

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
        // modelListText 不再整体可点，每个模型独立 Button
        binding.sendButton.setOnClickListener { onSendClick() }
        binding.clearButton.setOnClickListener { onClearClick() }

        // 软键盘「发送」键也触发发送
        binding.inputEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                onSendClick(); true
            } else false
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

        // 清掉容器里的旧 Button（保留第一个 TextView placeholder）
        val container = binding.modelListContainer
        // 只保留第一个子 View（modelListText）
        while (container.childCount > 1) {
            container.removeViewAt(1)
        }

        if (scannedModels.isEmpty()) {
            binding.modelListText.text = getString(R.string.model_none)
            binding.modelListText.visibility = android.view.View.VISIBLE
        } else {
            binding.modelListText.visibility = android.view.View.GONE
            // 为每个模型创建一个 Button，点了直接加载
            scannedModels.forEach { m ->
                val btn = android.widget.Button(this).apply {
                    text = "加载: ${m.name}  (${m.sizeText}, ${m.source})"
                    isAllCaps = false
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
        // SAF: 用通配类型确保能选到 gguf（部分设备不识别 application/octet-stream）
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

        Thread {
            unloadModel() // 若之前已加载过，先卸载
            val result = loadModelNative(info.path)
            android.util.Log.i("LlamaAndroid", "loadModelNative result='$result' path='${info.path}'")
            runOnUiThread {
                if (result.startsWith("OK")) {
                    modelReady = true
                    binding.statusText.text = getString(R.string.model_loaded, info.name)
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
        binding.inputEdit.setText("")
        updateUi()

        appendChat("User", question)
        appendChat("Assistant", "")

        Thread {
            try {
                val raw = runChat(question)
                // 解析性能包
                val (answer, perf) = parseResult(raw)
                runOnUiThread {
                    replaceLastAssistant(answer)
                    binding.perfText.text = perf
                    binding.statusText.text = getString(R.string.status_ready)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    replaceLastAssistant("[ERROR] ${e.message}")
                    binding.statusText.text = "ERROR: ${e.message}"
                }
            } finally {
                generating = false
                runOnUiThread { updateUi() }
            }
        }.start()
    }

    private fun onClearClick() {
        clearChat()
        binding.resultText.text = getString(R.string.result_placeholder)
        binding.perfText.text = ""
        binding.statusText.text = if (modelReady) getString(R.string.status_ready)
                                  else getString(R.string.chat_disabled)
    }

    /**
     * 解析 native 返回：answer<<<PERF>>>k=v;k=v<<<END>>>
     * 若无 PERF 段：answer=raw，perf="（无性能数据）"
     */
    private fun parseResult(raw: String): Pair<String, String> {
        val begin = raw.indexOf("<<<PERF>>>")
        val end   = raw.indexOf("<<<END>>>")
        if (begin < 0 || end < 0 || end <= begin) {
            return Pair(raw, "（无性能数据）")
        }
        val answer = raw.substring(0, begin)
        val perfRaw = raw.substring(begin + "<<<PERF>>>".length, end)
        // 把 ; 分隔的键值对格式化成可读行
        val parts = perfRaw.split(";").filter { it.contains("=") }
        val sb = StringBuilder()
        for (p in parts) {
            val kv = p.split("=", limit = 2)
            if (kv.size == 2) {
                if (sb.isNotEmpty()) sb.append("  |  ")
                sb.append(kv[0]).append("=").append(kv[1])
            }
        }
        return Pair(answer, sb.toString())
    }

    private fun appendChat(role: String, content: String) {
        val sb = StringBuilder(binding.resultText.text)
        if (sb.isNotEmpty() && sb.toString() != getString(R.string.result_placeholder)) {
            sb.append("\n\n")
        }
        sb.append(role).append(": ").append(content)
        binding.resultText.text = sb.toString()
        scrollToEnd()
    }

    private fun replaceLastAssistant(answer: String) {
        val current = binding.resultText.text.toString()
        val marker = "\n\nAssistant: "
        val idx = current.lastIndexOf(marker)
        if (idx >= 0) {
            binding.resultText.text = current.substring(0, idx + marker.length) + answer
        } else {
            binding.resultText.text = "Assistant: $answer"
        }
        scrollToEnd()
    }

    private fun scrollToEnd() {
        binding.resultScroll.post {
            binding.resultScroll.fullScroll(android.view.View.FOCUS_DOWN)
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
    private external fun loadModelNative(modelPath: String): String
    private external fun runChat(userInput: String): String
    private external fun clearChat()
    private external fun unloadModel()

    companion object {
        init {
            System.loadLibrary("llamaandroid")
        }
    }
}
