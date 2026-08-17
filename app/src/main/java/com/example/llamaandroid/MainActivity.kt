package com.example.llamaandroid

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.llamaandroid.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var modelReady = false
    private var generating = false

    private val modelName = "qwen3.5-0.8b-Q4_K_M.gguf"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.sendButton.setOnClickListener { onSendClick() }
        binding.clearButton.setOnClickListener { onClearClick() }
        updateUi()

        prepareAndLoadModel()
    }

    /**
     * 1. 后台把模型从 assets 复制到 filesDir（首次）
     * 2. 加载模型（只加载一次，全程复用）
     */
    private fun prepareAndLoadModel() {
        binding.statusText.text = getString(R.string.status_initializing)

        Thread {
            try {
                val modelFile = File(filesDir, modelName)
                if (!modelFile.exists()) {
                    runOnUiThread { binding.statusText.text = getString(R.string.status_copying) }
                    assets.open(modelName).use { input ->
                        modelFile.outputStream().use { output ->
                            val buffer = ByteArray(1024 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n <= 0) break
                                output.write(buffer, 0, n)
                            }
                        }
                    }
                }
                if (!modelFile.exists()) throw Exception("模型文件复制后仍不存在")

                runOnUiThread { binding.statusText.text = getString(R.string.status_loading) }

                // 调用 JNI loadModel
                val loadResult = loadModel(modelFile.absolutePath)
                if (!loadResult.startsWith("OK")) {
                    runOnUiThread {
                        binding.statusText.text = "ERROR: $loadResult"
                    }
                    return@Thread
                }

                runOnUiThread {
                    binding.statusText.text = getString(R.string.status_ready)
                    modelReady = true
                    updateUi()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.statusText.text = "ERROR: ${e.message}"
                }
            }
        }.start()
    }

    /**
     * 发：调 runChat，解析返回（answer + <<<PERF>>>...<<<END>>>），分别显示
     */
    private fun onSendClick() {
        if (!modelReady || generating) return
        val question = binding.inputEdit.text.toString().trim()
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
        binding.statusText.text = getString(R.string.status_ready)
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
        binding.sendButton.isEnabled = canSend
        binding.clearButton.isEnabled = modelReady && !generating
        binding.sendButton.text = if (generating) getString(R.string.status_generating)
                                  else getString(R.string.send)
        binding.inputEdit.isEnabled = canSend
    }

    // ===== JNI =====
    private external fun loadModel(modelPath: String): String
    private external fun runChat(userInput: String): String
    private external fun clearChat()
    private external fun unloadModel()

    companion object {
        init {
            System.loadLibrary("llamaandroid")
        }
    }
}
