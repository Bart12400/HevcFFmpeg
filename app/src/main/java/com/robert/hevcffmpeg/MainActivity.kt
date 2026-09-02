package com.robert.hevcffmpeg

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var tvConsole: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    private var currentFolder = File(Environment.getExternalStorageDirectory(), "DCIM/Camera")
    @Volatile private var isProcessing = false
    private var currentEngine: TranscoderEngine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(0xFF121212.toInt())
            setPadding(16, 16, 16, 16)
        }

        tvConsole = TextView(this).apply {
            setTextColor(0xFF00FF00.toInt())
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
        }

        scrollView = ScrollView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            addView(tvConsole)
        }

        val btnLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }

        btnStart = Button(this).apply { text = "START FULL SCAN" }
        btnStop = Button(this).apply { text = "STOP"; isEnabled = false }

        btnLayout.addView(btnStart)
        btnLayout.addView(btnStop)

        layout.addView(scrollView)
        layout.addView(btnLayout)
        setContentView(layout)

        checkPermissions()

        btnStart.setOnClickListener { startProcessing() }
        btnStop.setOnClickListener { stopProcessing() }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            }
        }
    }

    private fun appendLog(msg: String) {
        runOnUiThread {
            tvConsole.append("$msg\n")
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun startProcessing() {
        isProcessing = true
        btnStart.isEnabled = false
        btnStop.isEnabled = true

        val serviceIntent = Intent(this, TranscoderService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
        else startService(serviceIntent)

        appendLog("=== HevcFFmpeg v1.0 ===")
        appendLog("> Skanowanie folderu: ${currentFolder.absolutePath}")

        thread {
            val validExts = listOf("mp4", "gif", "wmv", "mpeg", "avi", "flv", "mov")
            val files = currentFolder.walkTopDown()
                .filter { it.isFile && validExts.contains(it.extension.lowercase()) }
                .filter { !it.name.contains("_hevc.mp4", true) && !it.name.contains("_temp_HEVC.mp4", true) && !it.name.contains("_skiplowBR", true) }
                .toList()

            if (files.isEmpty()) {
                appendLog("> Brak plikow do przetworzenia.")
                runOnUiThread { resetUi() }
                return@thread
            }

            appendLog("> Znaleziono ${files.size} plikow.")
            val stats = SessionStats()

            for ((idx, file) in files.withIndex()) {
                if (!isProcessing) break

                val statusText = "[${idx + 1}/${files.size}] ${file.name}"
                startService(Intent(this, TranscoderService::class.java).apply { putExtra("STATUS", statusText) })

                val engine = TranscoderEngine(file, "mediacodec") { log -> appendLog(log) }
                currentEngine = engine
                engine.process(stats)
            }

            appendLog("\n=== PODSUMOWANIE SESJI ===")
            appendLog("Laczny zysk: Odzyskano ${stats.savedMb} MB.")
            appendLog("Statystyki wideo:")
            appendLog("  - HEVC do HEVC: ${stats.countH2H}")
            appendLog("  - AVC do HEVC: ${stats.countA2H}")
            appendLog("  - Inne do HEVC: ${stats.countO2H}")
            appendLog("  - do AAC 64k: ${stats.countAudioAac}")
            appendLog("Sekcja Pominięć:")
            appendLog("  - w formacie HEVC: ${stats.countSkipHevc}")
            appendLog("  - inny kodek (Low BR): ${stats.countSkipLow}")

            runOnUiThread { resetUi() }
        }
    }

    private fun stopProcessing() {
        isProcessing = false
        currentEngine?.cancel()
        resetUi()
        appendLog("> Przerwano operacje.")
    }

    private fun resetUi() {
        stopService(Intent(this, TranscoderService::class.java))
        btnStart.isEnabled = true
        btnStop.isEnabled = false
    }
}
