package com.example.llamaandroid

import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File

/**
 * 模型管理：负责扫描本地 GGUF 文件、导入（从 SAF URI 拷贝到 app 私有目录）。
 *
 * - modelsDir: app 私有目录 filesDir/models/，存放用户导入的模型副本
 * - 扫描范围：私有目录 + 公共 Download 目录（只读展示，加载时直接用其路径）
 */
class ModelManager(private val context: Context) {

    data class ModelInfo(
        val name: String,        // 文件名（含 .gguf）
        val path: String,        // 绝对路径，可直接传给 native loadModel
        val sizeBytes: Long,
        val sizeText: String,    // 人类可读大小
        val source: String       // "app" 或 "download"
    )

    val modelsDir: File = File(context.filesDir, "models").apply { if (!exists()) mkdirs() }

    /**
     * 扫描所有可用模型：app 私有目录 + 公共 Download 目录。
     * 同名时优先 app 私有目录（已导入的）。
     */
    fun scan(): List<ModelInfo> {
        val result = LinkedHashMap<String, ModelInfo>()

        // 1. app 私有目录
        modelsDir.listFiles()?.filter { f ->
            f.isFile && f.name.endsWith(".gguf", ignoreCase = true)
        }?.forEach { f ->
            result[f.name] = toInfo(f, "app")
        }

        // 2. 公共 Download 目录（只读，不需权限，API 21+ 公共 Download 始终可读）
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        downloadDir.listFiles()?.filter { f ->
            f.isFile && f.name.endsWith(".gguf", ignoreCase = true)
        }?.forEach { f ->
            if (!result.containsKey(f.name)) {
                result[f.name] = toInfo(f, "download")
            }
        }

        return result.values.toList()
    }

    private fun toInfo(f: File, source: String) = ModelInfo(
        name = f.name,
        path = f.absolutePath,
        sizeBytes = f.length(),
        sizeText = formatSize(f.length()),
        source = source
    )

    /**
     * 从 SAF 返回的 content URI 导入模型到 app 私有目录。
     * @param uri 用户通过 ACTION_OPEN_DOCUMENT 选中的 gguf 文件 URI
     * @param displayName 文件名（从 URI cursor 取）
     * @return 导入后的本地 File，或 null 表示失败
     */
    fun importFromUri(uri: Uri, displayName: String): File? {
        return try {
            if (!displayName.endsWith(".gguf", ignoreCase = true)) return null
            val target = File(modelsDir, displayName)
            if (target.exists()) target.delete()
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 1024 * 1024)
                }
            } ?: return null
            target
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 删除 app 私有目录中已导入的模型。
     * 仅对 source=app 的模型有效（Download 目录的只读，不可删）。
     * @return true 删除成功
     */
    fun deleteModel(name: String): Boolean {
        val f = File(modelsDir, name)
        return f.exists() && f.isFile && f.delete()
    }

    companion object {
        fun formatSize(bytes: Long): String = when {
            bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1_000_000_000.0)
            bytes >= 1_000_000     -> "%.0f MB".format(bytes / 1_000_000.0)
            bytes >= 1_000         -> "%.0f KB".format(bytes / 1_000.0)
            else                   -> "$bytes B"
        }
    }
}
