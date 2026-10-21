package com.sync.wamusic

import android.app.*
import android.content.*
import android.graphics.Color
import android.media.MediaPlayer
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.Gravity
import android.widget.*
import kotlin.math.abs

class WhatsAppSyncService : NotificationListenerService() {

    companion object {
        val deviceId: String = (1000..9999).random().toString()
        var replyAction: Notification.Action? = null
        var lastSenderTitle: String = "Sin conectar"
        var clockOffsetMs: Long = 0L
        var lastRttMs: Long = 450L
        var lastProcessedPacket: String = ""
        var onCommandReceived: ((cmd: String, posMs: Long, transitMs: Long) -> Unit)? = null
        var onTunnelUpdated: (() -> Unit)? = null

        fun sendWhatsAppPayload(context: Context, cmd: String, posMs: Long, extraTime: Long = System.currentTimeMillis()): Boolean {
            val action = replyAction ?: return false
            val remoteInputs = action.remoteInputs ?: return false
            val payload = "SYNC|$deviceId|$cmd|$posMs|$extraTime"

            val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            val bundle = Bundle()
            for (ri in remoteInputs) {
                bundle.putCharSequence(ri.resultKey, payload)
            }
            RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)
            return try {
                action.actionIntent.send(context, 0, intent)
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (!pkg.contains("whatsapp")) return

        val notif = sbn.notification ?: return
        if ((notif.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val extras = notif.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "Contacto"
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return

        // Buscar accion RemoteInput (boton Responder de WhatsApp)
        val allActions = mutableListOf<Notification.Action>()
        notif.actions?.let { allActions.addAll(it) }
        allActions.addAll(Notification.WearableExtender(notif).actions)

        val foundReply = allActions.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
        if (foundReply != null) {
            replyAction = foundReply
            lastSenderTitle = title
            onTunnelUpdated?.invoke()
        }

        // Handshake rapido escribiendo !sync en el chat
        if (text.trim().equals("!sync", ignoreCase = true)) {
            cancelNotification(sbn.key)
            sendWhatsAppPayload(applicationContext, "PING", 0L, System.currentTimeMillis())
            return
        }

        if (!text.startsWith("SYNC|")) return

        val parts = text.split("|")
        if (parts.size < 5) return

        val senderId = parts[1]
        // Ignorar eco de nuestros propios mensajes
        if (senderId == deviceId) return

        // Evitar procesar dos veces la misma notificacion
        if (text == lastProcessedPacket) return
        lastProcessedPacket = text

        // Borrar notificacion silenciosamente para que no moleste
        cancelNotification(sbn.key)

        val cmd = parts[2]
        val posMs = parts[3].toLongOrNull() ?: 0L
        val sentTime = parts[4].toLongOrNull() ?: 0L
        val now = System.currentTimeMillis()

        when (cmd) {
            "PING" -> {
                // Respondemos PONG enviando el tiempo original y nuestro tiempo de recepcion
                sendWhatsAppPayload(applicationContext, "PONG", sentTime, now)
            }
            "PONG" -> {
                // posMs trae t1 (cuando enviamos PING) y sentTime trae t2 (reloj del amigo)
                val rtt = (now - posMs).coerceAtLeast(50L)
                lastRttMs = rtt
                val oneWay = rtt / 2L
                clockOffsetMs = sentTime - (posMs + oneWay)
                onTunnelUpdated?.invoke()
            }
            "PLAY", "PAUSE", "SEEK" -> {
                val estimatedServerNow = now + clockOffsetMs
                val rawTransit = estimatedServerNow - sentTime
                val transitMs = if (rawTransit in 50L..4000L) rawTransit else (lastRttMs / 2L)
                onCommandReceived?.invoke(cmd, posMs, transitMs)
            }
        }
    }
}

class MainActivity : Activity() {

    private var mediaPlayer: MediaPlayer? = null
    private var isLocalPrepared = false
    private var useExternalPlayer = false
    private var isApplyingRemote = false
    private var activeController: MediaController? = null

    private lateinit var tvTunnelStatus: TextView
    private lateinit var tvSongStatus: TextView
    private lateinit var seekBar: SeekBar
    private val uiHandler = Handler(Looper.getMainLooper())

    private val externalCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            if (state == null || isApplyingRemote || !useExternalPlayer) return
            val pos = state.position
            when (state.state) {
                PlaybackState.STATE_PLAYING -> {
                    WhatsAppSyncService.sendWhatsAppPayload(this@MainActivity, "PLAY", pos)
                }
                PlaybackState.STATE_PAUSED -> {
                    WhatsAppSyncService.sendWhatsAppPayload(this@MainActivity, "PAUSE", pos)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
            setBackgroundColor(Color.parseColor("#121212"))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val title = TextView(this).apply {
            text = "WA Music Sync P2P"
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 24)
        }
        root.addView(title)

        tvTunnelStatus = TextView(this).apply {
            text = "Túnel WhatsApp: Desconectado\n(Pídele a tu amigo que te envíe !sync por WhatsApp)"
            textSize = 14f
            setTextColor(Color.parseColor("#4ADE80"))
            setPadding(0, 0, 0, 32)
        }
        root.addView(tvTunnelStatus)

        val btnPerm = Button(this).apply {
            text = "1. Activar Permiso de Notificaciones"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        root.addView(btnPerm)

        val btnPing = Button(this).apply {
            text = "2. Calibrar Reloj / Latencia (PING)"
            setOnClickListener {
                val ok = WhatsAppSyncService.sendWhatsAppPayload(this@MainActivity, "PING", 0L)
                Toast.makeText(
                    this@MainActivity,
                    if (ok) "PING enviado por WhatsApp" else "Aún no llega notificación de WhatsApp",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        root.addView(btnPing)

        val swMode = Switch(this).apply {
            text = "Modo Host: Controlar Reproductor Externo (Spotify / YT Music)"
            setTextColor(Color.WHITE)
            setPadding(0, 32, 0, 24)
            setOnCheckedChangeListener { _, isChecked ->
                useExternalPlayer = isChecked
                if (isChecked) attachExternalMediaSession()
                updateStatusUI()
            }
        }
        root.addView(swMode)

        val btnPickFile = Button(this).apply {
            text = "3. Elegir Audio Local (.mp3 / .m4a de WhatsApp)"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "audio/*"
                }
                startActivityForResult(intent, 101)
            }
        }
        root.addView(btnPickFile)

        tvSongStatus = TextView(this).apply {
            text = "Sin pista cargada"
            textSize = 15f
            setTextColor(Color.LTGRAY)
            setPadding(0, 32, 0, 16)
        }
        root.addView(tvSongStatus)

        seekBar = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    val pos = (sb?.progress ?: 0).toLong()
                    seekLocalOrExternal(pos)
                    WhatsAppSyncService.sendWhatsAppPayload(this@MainActivity, "SEEK", pos)
                }
            })
        }
        root.addView(seekBar)

        val controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 0)
        }

        val btnPlay = Button(this).apply {
            text = "▶ PLAY SYNC"
            setOnClickListener {
                val pos = getCurrentPositionMs()
                playLocalOrExternal(pos)
                WhatsAppSyncService.sendWhatsAppPayload(this@MainActivity, "PLAY", pos)
            }
        }

        val btnPause = Button(this).apply {
            text = "⏸ PAUSE SYNC"
            setOnClickListener {
                val pos = getCurrentPositionMs()
                pauseLocalOrExternal(pos)
                WhatsAppSyncService.sendWhatsAppPayload(this@MainActivity, "PAUSE", pos)
            }
        }

        controlsRow.addView(btnPlay)
        controlsRow.addView(btnPause)
        root.addView(controlsRow)

        setContentView(root)

        WhatsAppSyncService.onTunnelUpdated = {
            runOnUiThread { updateStatusUI() }
        }

        WhatsAppSyncService.onCommandReceived = { cmd, posMs, transitMs ->
            runOnUiThread {
                isApplyingRemote = true
                when (cmd) {
                    "PLAY" -> {
                        val compensatedPos = posMs + transitMs
                        playLocalOrExternal(compensatedPos)
                    }
                    "PAUSE" -> {
                        pauseLocalOrExternal(posMs)
                    }
                    "SEEK" -> {
                        seekLocalOrExternal(posMs)
                    }
                }
                uiHandler.postDelayed({ isApplyingRemote = false }, 600)
            }
        }

        startProgressUpdater()
    }

    private fun attachExternalMediaSession() {
        try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val cn = ComponentName(this, WhatsAppSyncService::class.java)
            val controllers = msm.getActiveSessions(cn)
            activeController?.unregisterCallback(externalCallback)
            activeController = controllers.firstOrNull()
            activeController?.registerCallback(externalCallback)
        } catch (e: SecurityException) {
            Toast.makeText(this, "Activa primero el permiso de Notificaciones", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getCurrentPositionMs(): Long {
        return if (useExternalPlayer) {
            attachExternalMediaSession()
            activeController?.playbackState?.position ?: 0L
        } else {
            mediaPlayer?.currentPosition?.toLong() ?: 0L
        }
    }

    private fun playLocalOrExternal(targetPosMs: Long) {
        if (useExternalPlayer) {
            attachExternalMediaSession()
            activeController?.transportControls?.seekTo(targetPosMs)
            activeController?.transportControls?.play()
        } else if (isLocalPrepared) {
            mediaPlayer?.seekTo(targetPosMs.toInt())
            mediaPlayer?.start()
        }
    }

    private fun pauseLocalOrExternal(targetPosMs: Long) {
        if (useExternalPlayer) {
            attachExternalMediaSession()
            activeController?.transportControls?.pause()
            activeController?.transportControls?.seekTo(targetPosMs)
        } else if (isLocalPrepared) {
            if (mediaPlayer?.isPlaying == true) mediaPlayer?.pause()
            mediaPlayer?.seekTo(targetPosMs.toInt())
        }
    }

    private fun seekLocalOrExternal(targetPosMs: Long) {
        if (useExternalPlayer) {
            attachExternalMediaSession()
            activeController?.transportControls?.seekTo(targetPosMs)
        } else if (isLocalPrepared) {
            mediaPlayer?.seekTo(targetPosMs.toInt())
        }
    }

    private fun updateStatusUI() {
        val connected = WhatsAppSyncService.replyAction != null
        tvTunnelStatus.text = if (connected) {
            "Túnel Activo con: ${WhatsAppSyncService.lastSenderTitle}\n" +
            "RTT: ${WhatsAppSyncService.lastRttMs} ms | Offset Reloj: ${WhatsAppSyncService.clockOffsetMs} ms"
        } else {
            "Túnel WhatsApp: Esperando mensaje...\n(Tu amigo debe enviarte !sync por WhatsApp)"
        }
    }

    private fun startProgressUpdater() {
        uiHandler.post(object : Runnable {
            override fun run() {
                if (!useExternalPlayer && isLocalPrepared) {
                    val dur = mediaPlayer?.duration ?: 1
                    val cur = mediaPlayer?.currentPosition ?: 0
                    seekBar.max = dur
                    seekBar.progress = cur
                    tvSongStatus.text = "Reproductor Local: ${cur / 1000}s / ${dur / 1000}s"
                } else if (useExternalPlayer) {
                    val pos = activeController?.playbackState?.position ?: 0L
                    tvSongStatus.text = "Reproductor Externo: ${pos / 1000}s"
                }
                uiHandler.postDelayed(this, 500)
            }
        })
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 101 && resultCode == RESULT_OK) {
            val uri: Uri = data?.data ?: return
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@MainActivity, uri)
                prepare()
                isLocalPrepared = true
            }
            tvSongStatus.text = "Audio local cargado y listo"
        }
    }
}
