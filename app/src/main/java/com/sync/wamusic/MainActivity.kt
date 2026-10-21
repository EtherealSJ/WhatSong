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
import android.widget.TextView
import android.widget.Toast
import java.io.File

object MusicEngine {
    private var player: MediaPlayer? = null
    private var currentFilePath: String = ""
    var lastExecutedState: String = ""
    var lastFileName: String = "Ninguno todavía"
    var onUiUpdate: (() -> Unit)? = null

    fun processChatCommand(context: Context, rawCmd: String) {
        val clean = rawCmd.trim().lowercase()
        if (!clean.startsWith("!")) return

        // Evitar que el mismo comando en pantalla se ejecute repetidas veces al hacer scroll
        if (clean == lastExecutedState) return

        when {
            clean == "!play" || clean == "!continuar" -> {
                lastExecutedState = clean
                ensureLatestAudioLoaded(context, false)
                player?.start()
                showToast(context, "Radio Sync: ▶ PLAY")
            }
            clean == "!reiniciar" -> {
                lastExecutedState = clean
                ensureLatestAudioLoaded(context, true)
                player?.seekTo(0)
                player?.start()
                showToast(context, "Radio Sync: ⏮ Reiniciando canción")
            }
            clean == "!pausa" || clean == "!pause" -> {
                lastExecutedState = clean
                if (player?.isPlaying == true) {
                    player?.pause()
                    showToast(context, "Radio Sync: ⏸ PAUSA")
                }
            }
            clean == "!stop" -> {
                lastExecutedState = clean
                player?.pause()
                player?.seekTo(0)
                showToast(context, "Radio Sync: ⏹ STOP")
            }
            clean.startsWith("!ir ") -> {
                lastExecutedState = clean
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
        if (latestFile == null) {
            showToast(context, "No se encontró audio en WhatsApp. Descárgalo en el chat.")
            return
        }

        if (!forceReload && latestFile.absolutePath == currentFilePath && player != null) {
            return
        }

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
            showToast(context, "Error al cargar audio: ${latestFile.name}")
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
        val texts = ArrayList<String>()
        collectScreenTexts(root, texts)

        // Verificar que estamos viendo el grupo "Radio Sync"
        val inRadioSync = texts.any { it.lowercase().contains("radio sync") }
        if (!inRadioSync) return

        // Buscar el ultimo comando escrito abajo del todo en el chat
        var latestCmd: String? = null
        for (t in texts) {
            val clean = t.trim().lowercase()
            if (clean == "!play" || clean == "!pausa" || clean == "!pause" ||
                clean == "!reiniciar" || clean == "!continuar" ||
                clean == "!stop" || clean.startsWith("!ir ")
            ) {
                latestCmd = clean
            }
        }

        if (latestCmd != null) {
            MusicEngine.processChatCommand(applicationContext, latestCmd)
        }
    }

    private fun collectScreenTexts(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        if (node == null) return
        // Ignorar la caja de texto donde escribes para que no se active antes de presionar Enviar
        if (node.isEditable) return

        val txt = node.text?.toString()
        if (!txt.isNullOrBlank()) {
            out.add(txt)
        }

        val count = node.childCount
        for (i in 0 until count) {
            collectScreenTexts(node.getChild(i), out)
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
                MusicEngine.processChatCommand(applicationContext, clean)
            }
        }
    }
}

class MainActivity : Activity() {

    private lateinit var tvInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
            setBackgroundColor(Color.parseColor("#0F172A"))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val title = TextView(this).apply {
            text = "Radio Sync Automático"
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 24)
        }
        root.addView(title)

        val instructions = TextView(this).apply {
            text = "Activa estos 3 permisos una sola vez.\n\n" +
                "Luego cierra esta app, entra a tu grupo de WhatsApp 'Radio Sync', " +
                "envía cualquier canción y escribe en el chat:\n\n" +
                "• !play (reproduce el último audio recibido)\n" +
                "• !pausa (pausa en ambos teléfonos)\n" +
                "• !ir 1:30 (salta al minuto 1:30)\n" +
                "• !reiniciar (vuelve al inicio)"
            textSize = 14f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, 32)
        }
        root.addView(instructions)

        val btnStorage = Button(this).apply {
            text = "1. Dar Permiso a Archivos de WhatsApp"
            setOnClickListener { requestStorageAccess() }
        }
        root.addView(btnStorage)

        val btnAccess = Button(this).apply {
            text = "2. Activar Lector de Chat (Accesibilidad)"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        root.addView(btnAccess)

        val btnNotif = Button(this).apply {
            text = "3. Activar Segundo Plano (Notificaciones)"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        root.addView(btnNotif)

        tvInfo = TextView(this).apply {
            text = "Último audio detectado: ${MusicEngine.lastFileName}\n" +
                "Último comando: ${MusicEngine.lastExecutedState}"
            textSize = 15f
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, 48, 0, 0)
        }
        root.addView(tvInfo)

        setContentView(root)

        MusicEngine.onUiUpdate = {
            runOnUiThread {
                tvInfo.text = "Último audio detectado: ${MusicEngine.lastFileName}\n" +
                    "Último comando: ${MusicEngine.lastExecutedState}"
            }
        }
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
