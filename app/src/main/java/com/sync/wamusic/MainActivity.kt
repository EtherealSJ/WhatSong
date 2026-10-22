package com.sync.wamusic

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.Gravity
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File

object MusicEngine {
    private var player: MediaPlayer? = null
    private var currentFilePath: String = ""
    var lastExecutedMsgId: String = "" // Evita que se repita al entrar y salir del chat
    var lastCommandText: String = "Ninguno"
    var lastFileName: String = "Ningún audio seleccionado"
    var onUiUpdate: (() -> Unit)? = null

    fun isPlaying(): Boolean = player?.isPlaying == true

    fun getCurrentPositionMs(): Int = player?.currentPosition ?: 0

    fun getDurationMs(): Int = player?.duration ?: 1

    fun processChatCommand(context: Context, msgId: String, rawCmd: String) {
        val clean = rawCmd.trim().lowercase()
        if (!clean.startsWith("!")) return

        // Si este mensaje exacto ya se ejecutó antes, lo ignoramos por completo
        if (msgId == lastExecutedMsgId && msgId.isNotEmpty()) return
        lastExecutedMsgId = msgId
        lastCommandText = clean

        when {
            clean == "!play" || clean == "!continuar" -> {
                ensureLatestAudioLoaded(context, false)
                player?.start()
                showToast(context, "Radio Sync: ▶ PLAY")
            }
            clean == "!reiniciar" -> {
                ensureLatestAudioLoaded(context, true)
                player?.seekTo(0)
                player?.start()
                showToast(context, "Radio Sync: ⏮ Reiniciando canción")
            }
            clean == "!pausa" || clean == "!pause" -> {
                if (player?.isPlaying == true) {
                    player?.pause()
                    showToast(context, "Radio Sync: ⏸ PAUSA")
                }
            }
            clean == "!stop" -> {
                player?.pause()
                player?.seekTo(0)
                showToast(context, "Radio Sync: ⏹ STOP")
            }
            clean.startsWith("!ir ") -> {
                val timePart = clean.removePrefix("!ir ").trim()
                val targetMs = parseTimeMs(timePart)
                ensureLatestAudioLoaded(context, false)
                player?.seekTo(targetMs)
                player?.start()
                showToast(context, "Radio Sync: ⏩ Saltando a ${targetMs / 1000}s")
            }
        }
        onUiUpdate?.invoke()
    }

    fun manualPlay(context: Context) {
        ensureLatestAudioLoaded(context, false)
        player?.start()
        onUiUpdate?.invoke()
    }

    fun manualPause() {
        if (player?.isPlaying == true) {
            player?.pause()
        }
        onUiUpdate?.invoke()
    }

    fun manualSeek(posMs: Int) {
        player?.seekTo(posMs)
        onUiUpdate?.invoke()
    }

    private fun parseTimeMs(text: String): Int {
        return try {
            if (text.contains(":")) {
                val parts = text.split(":")
                val min = parts[0].toIntOrNull() ?: 0
                val sec = parts[1].toIntOrNull() ?: 0
                (min * 60 + sec) * 1000
            } else {
                val sec = text.toIntOrNull() ?: 0
                sec * 1000
            }
        } catch (e: Exception) {
            0
        }
    }

    fun ensureLatestAudioLoaded(context: Context, forceReload: Boolean) {
        val latestFile = findNewestWhatsAppAudio()
        if (latestFile == null) return

        if (!forceReload && latestFile.absolutePath == currentFilePath && player != null) return

        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(latestFile.absolutePath)
                prepare()
            }
            currentFilePath = latestFile.absolutePath
            lastFileName = latestFile.name
            onUiUpdate?.invoke()
        } catch (e: Exception) {
            // Ignorar errores de lectura momentáneos
        }
    }

    private fun findNewestWhatsAppAudio(): File? {
        val root = Environment.getExternalStorageDirectory()
        val candidateDirs = listOf(
            File(root, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Audio"),
            File(root, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Audio/Sent"),
            File(root, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Audio/Private"),
            File(root, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes"),
            File(root, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents"),
            File(root, "WhatsApp/Media/WhatsApp Audio"),
            File(root, "WhatsApp/Media/WhatsApp Audio/Sent"),
            File(root, "WhatsApp/Media/WhatsApp Voice Notes"),
            File(root, "Download")
        )

        var newestFile: File? = null
        var newestTime: Long = 0L
        val validExts = listOf(".mp3", ".m4a", ".opus", ".ogg", ".aac", ".wav")

        for (dir in candidateDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            val files = dir.walkTopDown().maxDepth(2).filter { file ->
                file.isFile && validExts.any { ext -> file.name.lowercase().endsWith(ext) }
            }
            for (f in files) {
                if (f.lastModified() > newestTime) {
                    newestTime = f.lastModified()
                    newestFile = f
                }
            }
        }
        return newestFile
    }

    private fun showToast(context: Context, msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }
}

class WhatsAppChatService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val root = rootInActiveWindow ?: return
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(root, nodes)

        // Verificar que estamos viendo el grupo "Radio Sync"
        val inRadioSync = nodes.any { it.text?.toString()?.lowercase()?.contains("radio sync") == true }
        if (!inRadioSync) return

        // Buscar mensajes que sean comandos válidos
        for (node in nodes) {
            val txt = node.text?.toString()?.trim() ?: continue
            val lower = txt.lowercase()
            if (lower == "!play" || lower == "!pausa" || lower == "!pause" ||
                lower == "!reiniciar" || lower == "!continuar" ||
                lower == "!stop" || lower.startsWith("!ir ")
            ) {
                // Usamos el texto completo del nodo como identificador único para evitar que se repita
                val uniqueMsgId = txt + "_" + (node.windowId)
                MusicEngine.processChatCommand(applicationContext, uniqueMsgId, lower)
                break
            }
        }
    }

    private fun collectAllNodes(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        out.add(node)
        val count = node.childCount
        for (i in 0 until count) {
            collectAllNodes(node.getChild(i), out)
        }
    }

    override fun onInterrupt() {}
}

class WhatsAppNotifService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (!pkg.lowercase().contains("whatsapp")) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val conv = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return

        if ("$title $conv".lowercase().contains("radio sync")) {
            val clean = text.trim().lowercase()
            if (clean.startsWith("!")) {
                val uniqueId = clean + "_" + sbn.postTime
                MusicEngine.processChatCommand(applicationContext, uniqueId, clean)
            }
        }
    }
}

class MainActivity : Activity() {

    private lateinit var tvInfo: TextView
    private lateinit var tvTrack: TextView
    private lateinit var seekBar: SeekBar
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.parseColor("#0F172A"))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val title = TextView(this).apply {
            text = "Radio Sync Control Panel"
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 16)
        }
        root.addView(title)

        tvTrack = TextView(this).apply {
            text = "🎵 ${MusicEngine.lastFileName}"
            textSize = 15f
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, 0, 0, 16)
        }
        root.addView(tvTrack)

        seekBar = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    if (fromUser) {
                        val target = (s?.progress ?: 0)
                        MusicEngine.manualSeek(target)
                    }
                }
            })
        }
        root.addView(seekBar)

        val controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 24)
        }

        val btnPlay = Button(this).apply {
            text = "▶ PLAY"
            setOnClickListener { MusicEngine.manualPlay(this@MainActivity) }
        }
        val btnPause = Button(this).apply {
            text = "⏸ PAUSA"
            setOnClickListener { MusicEngine.manualPause() }
        }
        controlsRow.addView(btnPlay)
        controlsRow.addView(btnPause)
        root.addView(controlsRow)

        val instructions = TextView(this).apply {
            text = "Comandos en WhatsApp:\n" +
                "• !play (Reproducir)\n• !pausa (Pausar)\n" +
                "• !reiniciar (Volver a 0)\n• !ir 1:30 (Saltar al minuto 1:30)"
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, 24)
        }
        root.addView(instructions)

        val btnStorage = Button(this).apply {
            text = "1. Permiso Archivos WhatsApp"
            setOnClickListener { requestStorageAccess() }
        }
        root.addView(btnStorage)

        val btnAccess = Button(this).apply {
            text = "2. Activar Lector Chat (Accesibilidad)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        root.addView(btnAccess)

        val btnNotif = Button(this).apply {
            text = "3. Activar Notificaciones"
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
        root.addView(btnNotif)

        tvInfo = TextView(this).apply {
            text = "Último comando ejecutado: ${MusicEngine.lastCommandText}"
            textSize = 13f
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, 24, 0, 0)
        }
        root.addView(tvInfo)

        setContentView(root)

        MusicEngine.onUiUpdate = {
            runOnUiThread { updateUIState() }
        }

        startLoop()
    }

    private fun startLoop() {
        handler.post(object : Runnable {
            override fun run() {
                MusicEngine.ensureLatestAudioLoaded(this@MainActivity, false)
                val dur = MusicEngine.getDurationMs()
                val cur = MusicEngine.getCurrentPositionMs()
                seekBar.max = dur
                seekBar.progress = cur
                tvTrack.text = "🎵 ${MusicEngine.lastFileName}"
                tvInfo.text = "Último comando ejecutado: ${MusicEngine.lastCommandText}"
                handler.postDelayed(this, 500)
            }
        })
    }

    private fun updateUIState() {
        tvTrack.text = "🎵 ${MusicEngine.lastFileName}"
        tvInfo.text = "Último comando ejecutado: ${MusicEngine.lastCommandText}"
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 100)
        }
    }
}
