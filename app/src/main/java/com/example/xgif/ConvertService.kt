package com.example.xgif

import android.app.*
import android.content.Intent
import android.net.Uri
import android.os.*
import androidx.documentfile.provider.DocumentFile
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.UUID

data class TaskState(val busy: Boolean = false, val message: String = "貼上 X 連結，選擇資料夾即可開始。", val result: Uri? = null)

class ConvertService : Service() {
    companion object { val state = MutableStateFlow(TaskState()) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var processId: String? = null
    private var wake: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        if (state.value.busy) return START_NOT_STICKY
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("conversion", "GIF 轉換", NotificationManager.IMPORTANCE_LOW))
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(1, Notification.Builder(this, "conversion")
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("X → GIF")
            .setContentText("正在處理，完成後請回到 App 查看結果。")
            .setContentIntent(pending).setOngoing(true).build())
        state.value = TaskState(true, "正在初始化引擎…")
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xgif:convert").apply { acquire(30 * 60 * 1000L) }
        scope.launch {
            val dir = File(cacheDir, "job-${UUID.randomUUID()}")
            var created: DocumentFile? = null
            var committed = false
            try {
                // Only this service owns job directories; clear leftovers from a killed process.
                cacheDir.listFiles()?.filter { it.name.startsWith("job-") }?.forEach { it.deleteRecursively() }
                check(dir.mkdirs()) { "無法建立暫存目錄。" }
                YoutubeDL.getInstance().init(applicationContext)
                FFmpeg.getInstance().init(applicationContext)
                // 轉換前嘗試更新；更新失敗時，繼續使用現有引擎。
if (!intent.getBooleanExtra("update", false)) {
    state.value = TaskState(
        true,
        "正在檢查並更新 yt-dlp 引擎…"
    )

    try {
        val updateResult = YoutubeDL.getInstance().updateYoutubeDL(
            applicationContext,
            YoutubeDL.UpdateChannel.STABLE
        )

        check(updateResult != null) {
            "引擎更新未完成"
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        // 保留正常的工作取消行為。
        throw e
    } catch (e: Exception) {
        state.value = TaskState(
            true,
            "引擎更新失敗，繼續使用現有版本轉換…"
        )
    }
}
                if (intent.getBooleanExtra("update", false)) {
                    state.value = TaskState(true, "正在更新擷取引擎…")
                    YoutubeDL.getInstance().updateYoutubeDL(applicationContext)
                    state.value = TaskState(message = "擷取引擎已更新，可以再次嘗試轉換。")
                } else {
                    val url = XLink.normalize(intent.getStringExtra("url").orEmpty())
                    val tree = Uri.parse(requireNotNull(intent.getStringExtra("tree")))
                    val folder = DocumentFile.fromTreeUri(applicationContext, tree)
                    check(folder != null && folder.isDirectory && folder.canWrite()) { "無法寫入資料夾，請重新選擇保存位置。" }
                    val width = intent.getIntExtra("width", 480).also {
                        require(it in 100..720) {
                            "GIF 寬度必須介於 100–720 像素。"
                        }
                    }
                    val fps = intent.getIntExtra("fps", 24).also {
                        require(it in 1..50) {
                            "每秒幀數必須介於 1–50。"
                        }
                    }
                    val start = intent.getIntExtra("start", 0).also { require(it in 0..86400) }
                    val seconds = intent.getIntExtra("seconds", 10).also { require(it in 1..60) }
                    val index = intent.getIntExtra("index", 1).also { require(it in 1..4) }
                    val request = YoutubeDLRequest(url)
                    request.addOption("--no-config")
                    request.addOption("--playlist-items", index.toString())
                    request.addOption("--socket-timeout", "20")
                    request.addOption("--retries", "3")
                    request.addOption("--fragment-retries", "3")
                    request.addOption("--max-filesize", "200M")
                    request.addOption("-f", "best[ext=mp4]/best")
                    request.addOption("-o", File(dir, "clip.%(ext)s").absolutePath)
                    request.addOption("--recode-video", "gif")
                    // 保留來源畫面，按指定 FPS 重新安排播放時間。
                    val filter =
                        "trim=start=$start:duration=$seconds," +
                        "settb=AVTB," +
                        "setpts=N/($fps*TB)," +
                        "scale=w='min($width,iw)':h=-1:flags=lanczos," +
                        "split[a][b];" +
                        "[a]palettegen=stats_mode=diff[p];" +
                        "[b][p]paletteuse=dither=sierra2_4a[out]"

                    // GIF 的延遲單位為 1/100 秒，設定最後一幀的停留時間。
                    val lastFrameDelay = (100.0 / fps).toInt().coerceAtLeast(1)
                    
                    request.addOption(
                        "--postprocessor-args",
                        "VideoConvertor+ffmpeg_o:-filter_complex \"$filter\" " +
                            "-map -0 -map [out] -an -fps_mode passthrough " +
                           "-final_delay $lastFrameDelay -loop 0"
                    )
                    state.value = TaskState(true, "正在解析及下載第 $index 段影片…")
                    processId = UUID.randomUUID().toString()
                    YoutubeDL.getInstance().execute(request, processId, false) { progress, _, line ->
                        val msg = if (line.contains("VideoConvertor") || progress >= 100) "正在轉換 GIF，請稍候…"
                            else if (progress >= 0) "正在下載影片：${progress.toInt().coerceIn(0,100)}%" else "正在解析影片…"
                        state.value = TaskState(true, msg)
                    }
                    val gif = File(dir, "clip.gif")
                    check(gif.exists() && gif.length() > 32) { "沒有產生 GIF。請確認貼文包含影片，且起始秒數未超過影片長度。" }
                    val header = gif.inputStream().use { stream -> ByteArray(6).also { check(stream.read(it) == 6) } }.toString(Charsets.US_ASCII)
                    check(header == "GIF89a" || header == "GIF87a") { "轉換結果不是有效的 GIF。" }
                    state.value = TaskState(true, "正在保存至選定資料夾…")
                    val filename = "X_${url.substringAfterLast('/')}_${System.currentTimeMillis()}.gif"
                    created = folder.createFile("image/gif", filename) ?: error("無法建立 GIF 檔案。")
                    val output = contentResolver.openOutputStream(created!!.uri, "w") ?: error("無法開啟保存位置。")
                    output.use { out -> gif.inputStream().use { it.copyTo(out) } }
                    committed = true
                    state.value = TaskState(message = "已保存：$filename\n大小：${"%.1f".format(gif.length() / 1048576.0)} MB", result = created!!.uri)
                }
            } catch (e: Exception) {
                state.value = TaskState(message = "處理失敗：${e.message?.take(1200) ?: "未知錯誤"}\n\n請確認網絡、剩餘空間、貼文是否公開且含有影片。若 X 更新了介面，可按「更新擷取引擎」後重試。需要登入的貼文不支援。")
            } finally {
                if (!committed) runCatching { created?.delete() }
                dir.deleteRecursively()
                processId = null
                wake?.let { if (it.isHeld) it.release() }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        processId?.let { runCatching { YoutubeDL.getInstance().destroyProcessById(it) } }
        scope.cancel()
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
