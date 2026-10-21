package com.example.xgif

import android.Manifest
import android.content.*
import android.graphics.Color
import android.net.Uri
import android.os.*
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private lateinit var root: LinearLayout
    private lateinit var url: EditText
    private lateinit var folderText: TextView
    private lateinit var status: TextView
    private lateinit var start: EditText
    private lateinit var seconds: EditText
    private lateinit var width: Seekbar
    private lateinit var fps: Seekbar
    private lateinit var index: Spinner
    private lateinit var open: Button
    private lateinit var progress: ProgressBar
    private val controls = mutableListOf<View>()
    private var folder: Uri? = null
    private var result: Uri? = null
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val chooseFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                val old = folder
                folder = uri
                prefs.edit().putString("folder", uri.toString()).apply()
                if (old != null && old != uri) runCatching {
                    contentResolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
                showFolder()
            } catch (e: Exception) { status.text = "無法保存資料夾權限：${e.message}" }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        folder = prefs.getString("folder", null)?.let(Uri::parse)
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(28))
            setBackgroundColor(Color.rgb(246, 248, 252))
        }
        scroll.addView(root)
        setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        label("X → GIF", 32f)
        label("把喜歡的片段，變成循環動圖。", 16f)
        label("X 貼文連結")
        url = edit("https://x.com/用戶/status/…", false).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(savedInstanceState?.getString("url") ?: prefs.getString("url", ""))
        }
        button("貼上連結") {
            val clip = getSystemService(ClipboardManager::class.java).primaryClip
            if (clip != null && clip.itemCount > 0) url.setText(clip.getItemAt(0).coerceToText(this))
        }
        label("保存位置")
        folderText = label("")
        showFolder()
        button("選擇保存資料夾") { chooseFolder.launch(folder) }
        width = numberSlider(
        title = "GIF 寬度（像素，保留比例）",
        minimum = 100,
        maximum = 720,
        initial = savedInstanceState?.getInt("gifWidthValue", 480) ?: 480,
        unit = "px"
        )
        fps = numberSlider(
        title = "每秒幀數",
        minimum = 1,
        maximum = 50,
        initial = savedInstanceState?.getInt("gifFpsValue", 24) ?: 24,
        unit = "FPS"
        )
        label("起始秒數")
        start = edit("0", true).apply { setText(savedInstanceState?.getString("start") ?: "0") }
        label("片段長度（1–60 秒；預設取前 10 秒）")
        seconds = edit("10", true).apply { setText(savedInstanceState?.getString("seconds") ?: "10") }
        label("貼文中的第幾段影片")
        index = spinner(listOf("1", "2", "3", "4"), savedInstanceState?.getInt("index") ?: 0)
        button("下載並轉換 GIF") { begin(false) }
        progress = ProgressBar(this).apply { isIndeterminate = true; visibility = View.GONE }
        root.addView(progress)
        status = label("", 15f).apply { setTextIsSelectable(true) }
        open = Button(this).apply {
            text = "開啟已保存的 GIF"; visibility = View.GONE
            setOnClickListener {
                result?.let { uri ->
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/gif").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    } catch (_: ActivityNotFoundException) { status.text = "手機沒有可開啟 GIF 的應用程式，請從檔案管理器查看。" }
                }
            }
        }
        root.addView(open)
        button("更新擷取引擎") { begin(true) }
        label("GIF 沒有聲音。僅支援可公開存取的影片；純圖片、需要登入或受限制的貼文可能無法擷取。轉換全程在手機進行。", 13f)
        consumeShare(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ConvertService.state.collect { state ->
                    controls.forEach { it.isEnabled = !state.busy }
                    progress.visibility = if (state.busy) View.VISIBLE else View.GONE
                    status.text = state.message
                    result = state.result
                    open.visibility = if (result != null) View.VISIBLE else View.GONE
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33 && prefs.getBoolean("askNotification", true)) {
            prefs.edit().putBoolean("askNotification", false).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    private fun begin(update: Boolean) {
        if (ConvertService.state.value.busy) return
        try {
            val intent = Intent(this, ConvertService::class.java).putExtra("update", update)
            if (!update) {
                val normalized = XLink.normalize(url.text.toString())
                val tree = folder ?: throw IllegalArgumentException("請先選擇保存資料夾。")
                val offset = start.text.toString().toIntOrNull()
                val duration = seconds.text.toString().toIntOrNull()
                require(offset != null && offset in 0..86400) { "起始秒數必須是 0–86400 的整數。" }
                require(duration != null && duration in 1..60) { "片段長度必須是 1–60 秒。" }
                prefs.edit().putString("url", url.text.toString()).apply()
                intent.putExtra("url", normalized).putExtra("tree", tree.toString())
                    .putExtra("start", offset).putExtra("seconds", duration)
                    .putExtra("width", width.progress + 100)
                    .putExtra("fps", fps.progress + 1)
                    .putExtra("index", index.selectedItem.toString().toInt())
            }
            startForegroundService(intent)
        } catch (e: Exception) { status.text = e.message }
    }
    private fun showFolder() {
        folderText.text = folder?.let {
            val name = runCatching { DocumentFile.fromTreeUri(this, it)?.name }.getOrNull()
            "${name ?: "已選擇的資料夾"}\n${Uri.decode(it.lastPathSegment.orEmpty())}"
        } ?: "尚未選擇。建議在 Download 中建立 XGif 子資料夾。"
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); consumeShare(intent) }
    private fun consumeShare(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { url.setText(it) }
            intent.action = Intent.ACTION_MAIN
        }
    }
    override fun onSaveInstanceState(out: Bundle) {
        out.putString("url", url.text.toString()); out.putString("start", start.text.toString()); out.putString("seconds", seconds.text.toString())
        out.putInt("width", width.selectedItemPosition); out.putInt("fps", fps.selectedItemPosition); out.putInt("index", index.selectedItemPosition)
        super.onSaveInstanceState(out)
    }
    private fun label(text: String, size: Float = 14f): TextView = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(Color.rgb(24, 35, 52)); setPadding(0, dp(12), 0, dp(8)); root.addView(this)
    }
    private fun edit(hint: String, numeric: Boolean): EditText = EditText(this).apply {
        this.hint = hint
        inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        setSingleLine(true); root.addView(this); controls.add(this)
    }
    private fun button(text: String, action: () -> Unit): Button = Button(this).apply {
        this.text = text; isAllCaps = false; setOnClickListener { action() }; root.addView(this); controls.add(this)
    }
    private fun spinner(items: List<String>, selected: Int): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
        setSelection(selected); root.addView(this); controls.add(this)
    }
    private fun numberSlider(
    title: String,
    minimum: Int,
    maximum: Int,
    initial: Int,
    unit: String
    ): SeekBar {
    label(title)

    val valueText = label("", 20f)

    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
    }

    // progress 從 0 開始，實際值 = minimum + progress。
    val slider = SeekBar(this).apply {
        max = maximum - minimum
        progress = initial.coerceIn(minimum, maximum) - minimum
        keyProgressIncrement = 1
        contentDescription = title
        layoutParams = LinearLayout.LayoutParams(
            0,
            dp(48),
            1f
        )
    }

    fun refreshValue() {
        valueText.text = "${minimum + slider.progress} $unit"
    }

    slider.setOnSeekBarChangeListener(
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar?,
                progress: Int,
                fromUser: Boolean
            ) {
                refreshValue()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
    )

    fun stepButton(symbol: String, step: Int): Button {
        return Button(this).apply {
            text = symbol
            textSize = 18f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setPadding(0, 0, 0, 0)

            contentDescription =
                "$title${if (step < 0) "減少" else "增加"} 1"

            layoutParams = LinearLayout.LayoutParams(
                dp(48),
                dp(48)
            )

            setOnClickListener {
                slider.progress =
                    (slider.progress + step).coerceIn(0, slider.max)
            }

            // 轉換中與其他設定一起停用。
            controls.add(this)
        }
    }

    row.addView(slider)
    row.addView(stepButton("◀", -1))
    row.addView(stepButton("▶", 1))

    root.addView(row)
    controls.add(slider)
    refreshValue()

    return slider
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
