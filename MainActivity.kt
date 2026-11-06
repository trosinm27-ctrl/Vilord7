package com.vilord.scenecutter

import androidx.activity.ComponentActivity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity() {
    data class Scene(val start: Double, val end: Double)
    private val worker = Executors.newSingleThreadExecutor()
    private var inputUri: Uri? = null
    private var inputSaf: String? = null
    private var outputTree: Uri? = null
    private var durationSec = 0.0
    private val scenes = mutableListOf<Scene>()
    private var busy = false

    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var sceneList: LinearLayout
    private lateinit var detectButton: Button
    private lateinit var exportButton: Button
    private lateinit var startField: EditText
    private lateinit var endField: EditText
    private lateinit var thresholdField: EditText
    private lateinit var gapField: EditText

    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            inputUri = uri
            inputSaf = FFmpegKitConfig.getSafParameterForRead(this, uri, true)
            scenes.clear()
            refreshScenes()
            setStatus("Файл выбран. Читаем метаданные…")
            runProbe()
        }
    }
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            outputTree = uri
            contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            setStatus("Папка экспорта выбрана. Сцены будут сохранены туда.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(24))
            setBackgroundColor(0xFF0C0D12.toInt())
        }
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        body.addView(TextView(this).apply {
            text = "VILORD SCENE CUTTER"
            textSize = 22f; setTextColor(0xFFC0A8FF.toInt()); letterSpacing = .08f
            setPadding(0, dp(4), 0, dp(2))
        })
        body.addView(label("NATIVE ANDROID · ЛОКАЛЬНАЯ ОБРАБОТКА"))
        body.addView(button("Выбрать фильм (TS / MKV / MP4 / AVI / MOV)") {
            pickVideo.launch(arrayOf("video/*", "application/octet-stream", "*/*"))
        })
        body.addView(button("Выбрать папку для сохранения сцен") { pickFolder.launch(null) })
        body.addView(label("Порог обнаружения сцен (0.05–0.90)"))
        thresholdField = edit("0.30")
        body.addView(thresholdField)
        body.addView(label("Минимальный интервал между сменами, секунд"))
        gapField = edit("2")
        body.addView(gapField)
        detectButton = button("НАЙТИ СЦЕНЫ АВТОМАТИЧЕСКИ") { detectScenes() }
        body.addView(detectButton)
        body.addView(label("РУЧНАЯ НАРЕЗКА"))
        body.addView(label("Начало фрагмента, секунды"))
        startField = edit("0"); body.addView(startField)
        body.addView(label("Конец фрагмента, секунды"))
        endField = edit("0"); body.addView(endField)
        body.addView(button("Добавить ручной фрагмент") { addManualScene() })
        exportButton = button("ЭКСПОРТИРОВАТЬ ВСЕ СЦЕНЫ В MP4") { exportScenes() }
        body.addView(exportButton)
        body.addView(label("СПИСОК СЦЕН"))
        sceneList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(sceneList)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0
        }
        body.addView(progress, LinearLayout.LayoutParams(-1, dp(10)))
        status = TextView(this).apply {
            text = "Выбери исходный видеофайл. Видео не отправляется в интернет."
            textSize = 13f; setTextColor(0xFFB9B9C5.toInt()); setPadding(0, dp(12), 0, dp(8))
        }
        body.addView(status)
        setContentView(root)
        detectButton.isEnabled = false
        exportButton.isEnabled = false
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text; textSize = 12f; setTextColor(0xFFAAA5BA.toInt())
        setPadding(0, dp(12), 0, dp(5))
    }
    private fun edit(value: String) = EditText(this).apply {
        setText(value); textSize = 15f; setTextColor(0xFFFFFFFF.toInt())
        setHintTextColor(0xFF777788.toInt()); setSingleLine()
        setPadding(dp(12), dp(7), dp(12), dp(7))
        setBackgroundColor(0xFF1B1D29.toInt())
    }
    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 13f
        setTextColor(0xFFFFFFFF.toInt()); setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF7254C8.toInt()))
        setOnClickListener { action() }
        val lp = LinearLayout.LayoutParams(-1, dp(48)); lp.topMargin = dp(8)
        layoutParams = lp
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun setStatus(message: String) { runOnUiThread { status.text = message } }
    private fun setBusy(value: Boolean) {
        busy = value
        runOnUiThread {
            detectButton.isEnabled = !value && inputSaf != null
            exportButton.isEnabled = !value && scenes.isNotEmpty() && outputTree != null
        }
    }

    private fun runProbe() {
        val input = inputSaf ?: return
        worker.execute {
            val session = FFprobeKit.executeWithArguments(arrayOf(
                "-v", "error", "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1",
                input
            ))
            val output = session.allLogsAsString
            val value = Regex("""(?m)^([0-9]+(?:\\.[0-9]+)?)\\s*$""").find(output)?.groupValues?.get(1)?.toDoubleOrNull()
            if (value != null) {
                durationSec = value
                runOnUiThread { endField.setText(String.format(Locale.US, "%.2f", durationSec)); detectButton.isEnabled = true }
                setStatus("Видео готово · длительность ${time(value)}. Исходник читается напрямую через Android Storage Access Framework.")
            } else {
                // Probe with FFmpeg fallback: duration may not be available from some demuxers.
                setStatus("Файл выбран. Если длительность не определилась, можно запустить поиск сцен; FFmpeg прочитает контейнер напрямую.")
                runOnUiThread { detectButton.isEnabled = true }
            }
        }
    }

    private fun detectScenes() {
        val input = inputSaf ?: return
        if (busy) return
        val threshold = thresholdField.text.toString().toDoubleOrNull()?.coerceIn(.05, .90) ?: .30
        val gap = gapField.text.toString().toDoubleOrNull()?.coerceAtLeast(0.0) ?: 2.0
        scenes.clear(); refreshScenes(); setBusy(true); progress.progress = 0
        setStatus("Анализ всего фильма: декодирование кадров и поиск смен сцен. Не закрывай приложение.")
        val args = arrayOf(
            "-hide_banner", "-i", input,
            "-vf", "select='gt(scene,$threshold)',showinfo",
            "-an", "-f", "null", "-"
        )
        val cuts = mutableListOf<Double>()
        FFmpegKit.executeWithArgumentsAsync(args, { session ->
            val rc = session.returnCode
            if (ReturnCode.isSuccess(rc)) {
                val text = session.allLogsAsString
                val regex = Regex("""pts_time:([0-9]+(?:\\.[0-9]+)?)""")
                regex.findAll(text).forEach { it.groupValues[1].toDoubleOrNull()?.let { t ->
                    if (t > 0 && (cuts.isEmpty() || t - cuts.last() >= gap)) cuts.add(t)
                } }
                val total = durationSec.takeIf { it > 0 } ?: (cuts.lastOrNull() ?: 0.0) + 1.0
                val bounds = listOf(0.0) + cuts + total
                for (i in 0 until bounds.size - 1) if (bounds[i + 1] - bounds[i] > .05) scenes.add(Scene(bounds[i], bounds[i + 1]))
                runOnUiThread { refreshScenes(); progress.progress = 100 }
                setStatus("Анализ завершён. Найдено ${scenes.size} интервалов сцен.")
            } else {
                setStatus("Ошибка анализа: ${session.failStackTrace ?: session.allLogsAsString.takeLast(500)}")
            }
            setBusy(false)
        }, { log ->
            val match = Regex("""time=([0-9:.]+)""").find(log.message)
            if (match != null) {
                val sec = parseClock(match.groupValues[1])
                if (durationSec > 0) runOnUiThread { progress.progress = (sec / durationSec * 100).toInt().coerceIn(0, 99) }
            }
        }, { stats: Statistics ->
            if (durationSec > 0) runOnUiThread { progress.progress = (stats.time / (durationSec * 1000.0) * 100).toInt().coerceIn(0, 99) }
        })
    }

    private fun addManualScene() {
        val a = startField.text.toString().toDoubleOrNull()
        val b = endField.text.toString().toDoubleOrNull()
        if (a == null || b == null || a < 0 || b <= a || (durationSec > 0 && b > durationSec)) {
            setStatus("Проверь начало и конец: конец должен быть больше начала и не выходить за длительность.")
            return
        }
        scenes.add(Scene(a, b)); scenes.sortBy { it.start }; refreshScenes()
    }

    private fun refreshScenes() {
        if (!::sceneList.isInitialized) return
        runOnUiThread {
            sceneList.removeAllViews()
            scenes.forEachIndexed { index, scene ->
                val row = TextView(this).apply {
                    text = "Сцена ${index + 1}    ${time(scene.start)} — ${time(scene.end)}    (${String.format(Locale.US, "%.1f", scene.end - scene.start)} сек.)"
                    textSize = 13f; setTextColor(0xFFE6E2F0.toInt()); setPadding(dp(10), dp(12), dp(10), dp(12))
                    setBackgroundColor(0xFF1A1C26.toInt())
                    setOnClickListener {
                        startField.setText(scene.start.toString()); endField.setText(scene.end.toString())
                    }
                }
                val lp = LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(4); sceneList.addView(row, lp)
            }
            exportButton.isEnabled = !busy && scenes.isNotEmpty() && outputTree != null
        }
    }

    private fun exportScenes() {
        val tree = outputTree ?: run { setStatus("Сначала выбери папку для сохранения."); return }
        val input = inputSaf ?: return
        if (scenes.isEmpty() || busy) return
        setBusy(true); progress.progress = 0
        val snapshot = scenes.toList()
        worker.execute {
            var done = 0
            try {
                snapshot.forEachIndexed { index, scene ->
                    val name = String.format(Locale.US, "VILORD_SCENE_%03d.mp4", index + 1)
                    val doc = android.provider.DocumentsContract.createDocument(
                        contentResolver, tree, "video/mp4", name
                    ) ?: throw IllegalStateException("Не удалось создать $name в выбранной папке")
                    val output = FFmpegKitConfig.getSafParameterForWrite(this, doc)
                    val args = arrayOf(
                        "-hide_banner", "-y", "-ss", String.format(Locale.US, "%.3f", scene.start),
                        "-i", input, "-t", String.format(Locale.US, "%.3f", scene.end - scene.start),
                        "-map", "0:v:0?", "-map", "0:a:0?",
                        "-c:v", "libx264", "-preset", "ultrafast", "-crf", "21",
                        "-c:a", "aac", "-b:a", "160k", "-movflags", "+faststart",
                        "-avoid_negative_ts", "make_zero", output
                    )
                    val latch = java.util.concurrent.CountDownLatch(1)
                    var failure: String? = null
                    FFmpegKit.executeWithArgumentsAsync(args, { session ->
                        if (!ReturnCode.isSuccess(session.returnCode)) failure = session.allLogsAsString.takeLast(800)
                        latch.countDown()
                    }, { log ->
                        if (log.message.contains("time=")) {
                            setStatus("Экспорт ${index + 1}/${snapshot.size}: $name · ${log.message.take(100)}")
                        }
                    }, { stats ->
                        val overall = ((index + stats.time / max(1.0, (scene.end - scene.start) * 1000.0)) / snapshot.size * 100).toInt()
                        runOnUiThread { progress.progress = overall.coerceIn(0, 99) }
                    })
                    latch.await()
                    if (failure != null) throw IllegalStateException("Сцена ${index + 1}: $failure")
                    done++
                    runOnUiThread { progress.progress = (done * 100 / snapshot.size) }
                }
                setStatus("Готово: сохранено $done MP4-файлов в выбранную папку.")
            } catch (e: Exception) {
                setStatus("Экспорт остановлен: ${e.message}")
            } finally { setBusy(false); refreshScenes() }
        }
    }

    private fun parseClock(value: String): Double {
        val p = value.split(":")
        return try { when (p.size) { 3 -> p[0].toDouble()*3600+p[1].toDouble()*60+p[2].toDouble(); 2 -> p[0].toDouble()*60+p[1].toDouble(); else -> 0.0 } } catch (_: Exception) { 0.0 }
    }
    private fun time(seconds: Double): String {
        val s = seconds.toLong().coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d:%02d", s/3600, (s%3600)/60, s%60)
    }
    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }
}
