package com.example.llamaandroid

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.llamaandroid.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.sampleText.text =
            "正在准备 Qwen3.5-0.8B，请稍候..."

        Thread {

            try {
                // =========================
                // 1. GGUF 文件名
                // =========================

                val modelName =
                    "qwen3.5-0.8b-Q4_K_M.gguf"

                // =========================
                // 2. App 私有目录
                // =========================

                val modelFile = File(
                    filesDir,
                    modelName
                )

                // =========================
                // 3. 第一次运行：从 assets 复制
                // =========================

                if (!modelFile.exists()) {

                    runOnUiThread {
                        binding.sampleText.text =
                            "正在复制模型到内部存储，请稍候...\n模型大小约 505 MB"
                    }

                    assets.open(modelName).use { input ->

                        modelFile.outputStream().use { output ->

                            val buffer =
                                ByteArray(1024 * 1024)

                            while (true) {

                                val length =
                                    input.read(buffer)

                                if (length <= 0) {
                                    break
                                }

                                output.write(
                                    buffer,
                                    0,
                                    length
                                )
                            }
                        }
                    }
                }

                // =========================
                // 4. 检查文件
                // =========================

                if (!modelFile.exists()) {
                    throw Exception(
                        "模型文件不存在"
                    )
                }

                runOnUiThread {
                    binding.sampleText.text =
                        "模型准备完成，正在加载 Qwen3.5-0.8B..."
                }

                // =========================
                // 5. 调用 JNI
                // =========================

                val result =
                    runLlama(
                        modelFile.absolutePath
                    )

                // =========================
                // 6. 显示结果
                // =========================

                runOnUiThread {
                    binding.sampleText.text =
                        result
                }

            } catch (e: Exception) {

                runOnUiThread {
                    binding.sampleText.text =
                        "ERROR:\n${e.message}"
                }
            }

        }.start()
    }

    // JNI
    external fun runLlama(
        modelPath: String
    ): String

    companion object {

        init {
            System.loadLibrary(
                "llamaandroid"
            )
        }
    }
}