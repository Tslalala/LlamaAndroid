package com.example.llamaandroid

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.GravityCompat
import com.example.llamaandroid.databinding.ActivityHomeBinding

/**
 * 主页：模型管理入口。
 * - 扫描并列出本地 GGUF 模型，每张卡片可「进入对话」「删除」「模型设置」
 * - 导入模型（SAF）通过右下角 FAB
 * - onResume 自动刷新列表（无需刷新按钮）
 * - 外观设置抽屉：黑夜模式开关
 * - 模型级设置（思考模式/最大上下文）：每张卡片独立设置，持久化到 SharedPreferences
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var modelManager: ModelManager

    private var scannedModels: List<ModelManager.ModelInfo> = emptyList()

    // 最大上下文可选项（与 native clamp 范围一致）
    private val ctxOptions = intArrayOf(512, 1024, 2048, 4096)

    // 模型级设置存储 key 前缀
    private val prefs by lazy { getSharedPreferences("model_settings", MODE_PRIVATE) }

    // SAF 选择器
    private val openDocLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) importModel(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        modelManager = ModelManager(this)

        // FAB 导入
        binding.homeImportFab.setOnClickListener { onImportClick() }

        // 设置抽屉
        binding.homeSettingsButton.setOnClickListener {
            binding.homeDrawerLayout.openDrawer(GravityCompat.END)
        }
        binding.homeSettingsCloseButton.setOnClickListener {
            binding.homeDrawerLayout.closeDrawer(GravityCompat.END)
        }

        // 黑夜模式开关：读取上次设置
        val darkOn = prefs.getBoolean(KEY_DARK_MODE, false)
        binding.homeDarkModeSwitch.isChecked = darkOn
        binding.homeDarkModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            applyDarkMode(isChecked)
        }
        applyDarkMode(darkOn, fromInit = true)
    }

    override fun onResume() {
        super.onResume()
        // 返回主页时自动刷新（对话页可能卸载/删除模型，无需手动刷新按钮）
        refreshModelList()
    }

    private fun applyDarkMode(dark: Boolean, fromInit: Boolean = false) {
        AppCompatDelegate.setDefaultNightMode(
            if (dark) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
        if (!fromInit) {
            prefs.edit().putBoolean(KEY_DARK_MODE, dark).apply()
        }
    }

    // =========================
    // 模型列表渲染
    // =========================
    private fun refreshModelList() {
        scannedModels = modelManager.scan()
        android.util.Log.i("LlamaAndroid", "Home refreshModelList: found ${scannedModels.size} models")

        val container = binding.homeModelListContainer
        container.removeView(binding.homeModelListText)
        container.removeAllViews()
        container.addView(binding.homeModelListText)

        if (scannedModels.isEmpty()) {
            binding.homeModelListText.visibility = View.VISIBLE
            binding.homeStatusText.text = getString(R.string.home_status_empty)
            return
        }
        binding.homeModelListText.visibility = View.GONE
        binding.homeStatusText.text = getString(R.string.home_status_count, scannedModels.size)

        for (m in scannedModels) {
            container.addView(buildModelCard(m))
        }
    }

    /**
     * 构建单张模型卡片。
     * 布局：
     *   ┌──────────────────────────┐
     *   │ (Q)  模型名(加粗)      ✕  │  ← 首字母图标 + 名称 + 右上删除
     *   │      大小 · 来源标签        │
     *   │  ┌────────────────────┐   │
     *   │  │    进入对话         │   │  ← 全宽主按钮
     *   │  └────────────────────┘   │
     *   │  [思考:开] [上下文:2048]    │  ← 模型级设置状态 + 「设置」
     *   └──────────────────────────┘
     */
    private fun buildModelCard(m: ModelManager.ModelInfo): View {
        val ctx = this
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            val shape = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xFFFFFFFF.toInt())
                setStroke(1, 0xFFEEEEEE.toInt())
            }
            background = shape
            elevation = dp(3).toFloat()
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, 0, 0, dp(12))
            layoutParams = lp
        }

        // 第 1 行：首字母圆形图标 + 名称/大小信息 + 右上角删除
        val headRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val firstChar = m.name.firstOrNull { !it.isDigit() && it.isLetter() } ?: 'Q'
        val iconTv = TextView(ctx).apply {
            text = firstChar.uppercaseChar().toString()
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            val size = dp(40)
            val lp = LinearLayout.LayoutParams(size, size)
            lp.marginEnd = dp(12)
            layoutParams = lp
            val circle = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xFF1565C0.toInt())
            }
            background = circle
        }
        headRow.addView(iconTv)

        val infoBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
        }

        val nameTv = TextView(ctx).apply {
            text = m.name
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFF212121.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        infoBox.addView(nameTv)

        val isApp = m.source == "app"
        val metaColor = if (isApp) 0xFF2E7D32.toInt() else 0xFF1565C0.toInt()
        val metaBg    = if (isApp) 0xFFE8F5E9.toInt() else 0xFFE3F2FD.toInt()
        val metaTv = TextView(ctx).apply {
            text = "${m.sizeText}  ·  ${if (isApp) "已导入" else "Download"}"
            textSize = 11f
            setTextColor(metaColor)
            setPadding(dp(8), dp(2), dp(8), dp(2))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(4)
            layoutParams = lp
            val pill = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(metaBg)
            }
            background = pill
        }
        infoBox.addView(metaTv)
        headRow.addView(infoBox)

        if (isApp) {
            val delBtn = TextView(ctx).apply {
                text = "✕"
                textSize = 16f
                setTextColor(0xFFE57373.toInt())
                setPadding(dp(10), dp(6), dp(4), dp(6))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                layoutParams = lp
                setOnClickListener { confirmDelete(m) }
            }
            headRow.addView(delBtn)
        }
        card.addView(headRow)

        // 第 2 行：全宽「进入对话」主按钮
        val chatBtn = Button(ctx).apply {
            text = getString(R.string.home_chat)
            isAllCaps = false
            textSize = 14f
            minHeight = 0
            minimumHeight = 0
            setPadding(0, dp(12), 0, dp(12))
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF1565C0.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(16)
            layoutParams = lp
            val shape = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
            }
            background = shape
            setOnClickListener { startChat(m) }
        }
        card.addView(chatBtn)

        // 第 3 行：模型级设置状态 + 「设置」按钮
        val thinking = prefs.getBoolean(thinkingKey(m.name), false)
        val nCtx = prefs.getInt(ctxKey(m.name), 2048)
        val settingsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(10)
            layoutParams = lp
        }

        val tagThinking = TextView(ctx).apply {
            text = "思考: ${if (thinking) "开" else "关"}"
            textSize = 11f
            setTextColor(0xFF555555.toInt())
            setPadding(dp(8), dp(3), dp(8), dp(3))
            val pill = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(0xFFEFEFEF.toInt())
            }
            background = pill
        }
        settingsRow.addView(tagThinking)

        val tagCtx = TextView(ctx).apply {
            text = "上下文: $nCtx"
            textSize = 11f
            setTextColor(0xFF555555.toInt())
            setPadding(dp(8), dp(3), dp(8), dp(3))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginStart = dp(6)
            layoutParams = lp
            val pill = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(0xFFEFEFEF.toInt())
            }
            background = pill
        }
        settingsRow.addView(tagCtx)

        val settingsBtn = Button(ctx).apply {
            text = "设置"
            isAllCaps = false
            textSize = 11f
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(12), dp(4), dp(12), dp(4))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginStart = dp(6)
            layoutParams = lp
            val shape = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setStroke(1, 0xFF1565C0.toInt())
                setColor(0x00000000)
            }
            background = shape
            setTextColor(0xFF1565C0.toInt())
            setOnClickListener { showModelSettingsDialog(m) }
        }
        settingsRow.addView(settingsBtn)

        card.addView(settingsRow)

        return card
    }

    /**
     * 模型级设置对话框：思考模式 + 最大上下文。
     * 存储到 SharedPreferences，按模型名隔离。
     */
    private fun showModelSettingsDialog(m: ModelManager.ModelInfo) {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }

        // 思考模式
        val thinkRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val thinkLabel = TextView(this).apply {
            text = getString(R.string.thinking_mode)
            textSize = 15f
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
        }
        thinkRow.addView(thinkLabel)
        val thinkSwitch = Switch(this).apply {
            isChecked = prefs.getBoolean(thinkingKey(m.name), false)
        }
        thinkRow.addView(thinkSwitch)
        container.addView(thinkRow)

        val thinkHint = TextView(this).apply {
            text = getString(R.string.thinking_mode_hint)
            textSize = 11f
            setTextColor(0xFF888888.toInt())
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(16)
            layoutParams = lp
        }
        container.addView(thinkHint)

        // 最大上下文
        val ctxLabel = TextView(this).apply {
            text = getString(R.string.max_ctx)
            textSize = 15f
        }
        container.addView(ctxLabel)

        val ctxSpinner = android.widget.Spinner(this).apply {
            adapter = ArrayAdapter(
                this@HomeActivity, android.R.layout.simple_spinner_item,
                ctxOptions.map { "$it" }
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            val current = prefs.getInt(ctxKey(m.name), 2048)
            val idx = ctxOptions.indexOf(current).coerceAtLeast(0)
            setSelection(idx)
        }
        container.addView(ctxSpinner)

        val ctxHint = TextView(this).apply {
            text = getString(R.string.max_ctx_hint)
            textSize = 11f
            setTextColor(0xFF888888.toInt())
        }
        container.addView(ctxHint)

        AlertDialog.Builder(this)
            .setTitle("${m.name} · ${getString(R.string.per_model_settings)}")
            .setView(container)
            .setPositiveButton(getString(R.string.confirm)) { _, _ ->
                prefs.edit()
                    .putBoolean(thinkingKey(m.name), thinkSwitch.isChecked)
                    .putInt(ctxKey(m.name), ctxOptions[ctxSpinner.selectedItemPosition])
                    .apply()
                refreshModelList()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun thinkingKey(name: String) = "thinking_$name"
    private fun ctxKey(name: String) = "ctx_$name"

    // =========================
    // 进入对话
    // =========================
    private fun startChat(m: ModelManager.ModelInfo) {
        val nCtx = prefs.getInt(ctxKey(m.name), 2048)
        val thinking = prefs.getBoolean(thinkingKey(m.name), false)
        android.util.Log.i("LlamaAndroid", "Home startChat: ${m.name} nCtx=$nCtx thinking=$thinking")
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(EXTRA_MODEL_PATH, m.path)
            putExtra(EXTRA_MODEL_NAME, m.name)
            putExtra(EXTRA_N_CTX, nCtx)
            putExtra(EXTRA_THINKING, thinking)
        }
        startActivity(intent)
    }

    // =========================
    // 导入模型
    // =========================
    private fun onImportClick() {
        android.util.Log.i("LlamaAndroid", "Home onImportClick (FAB)")
        openDocLauncher.launch(arrayOf("*/*"))
    }

    private fun importModel(uri: Uri) {
        val name = queryDisplayName(uri) ?: "imported_${System.currentTimeMillis()}.gguf"
        if (!name.endsWith(".gguf", ignoreCase = true)) {
            toast("请选择 .gguf 文件"); return
        }
        binding.homeStatusText.text = getString(R.string.home_status_importing)
        Thread {
            val target = modelManager.importFromUri(uri, name)
            runOnUiThread {
                if (target != null) {
                    toast("导入成功: ${target.name}")
                    refreshModelList()
                } else {
                    binding.homeStatusText.text = "ERROR: 导入失败"
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

    // =========================
    // 删除模型（仅 app 导入的）
    // =========================
    private fun confirmDelete(m: ModelManager.ModelInfo) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.home_delete_confirm_title))
            .setMessage(getString(R.string.home_delete_confirm_msg, m.name))
            .setPositiveButton(getString(R.string.home_delete)) { _, _ ->
                if (modelManager.deleteModel(m.name)) {
                    toast(getString(R.string.home_deleted, m.name))
                    refreshModelList()
                } else {
                    toast("删除失败")
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_MODEL_PATH = "model_path"
        const val EXTRA_MODEL_NAME  = "model_name"
        const val EXTRA_N_CTX       = "n_ctx"
        const val EXTRA_THINKING    = "thinking"
        private const val KEY_DARK_MODE = "dark_mode"
    }
}
