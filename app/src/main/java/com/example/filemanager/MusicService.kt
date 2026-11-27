package com.example.filemanager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import java.io.File

/** Music ki halat. UI isse seedha padhta hai (service aur UI ek hi process me hain). */
object MusicState {
    var playlist by mutableStateOf<List<String>>(emptyList())
    var index by mutableIntStateOf(-1)
    var playing by mutableStateOf(false)
    var pos by mutableIntStateOf(0)
    var dur by mutableIntStateOf(0)
    var repeat by mutableIntStateOf(0)   // 0 = band, 1 = saari list, 2 = ek gaana
    var shuffle by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    val current: String? get() = playlist.getOrNull(index)
}

/** App band hone ya screen off hone par bhi gaana chalane wali foreground service */
class MusicService : Service() {

    companion object {
        const val ACTION_START = "com.example.filemanager.music.START"
        const val ACTION_TOGGLE = "com.example.filemanager.music.TOGGLE"
        const val ACTION_NEXT = "com.example.filemanager.music.NEXT"
        const val ACTION_PREV = "com.example.filemanager.music.PREV"
        const val ACTION_STOP = "com.example.filemanager.music.STOP"
        private const val CHANNEL = "music"
        private const val NOTIF_ID = 42

        @Volatile
        var instance: MusicService? = null

        fun start(context: Context, list: List<String>, index: Int) {
            val i = Intent(context, MusicService::class.java)
                .setAction(ACTION_START)
                .putStringArrayListExtra("list", ArrayList(list))
                .putExtra("index", index)
            ContextCompat.startForegroundService(context, i)
        }
    }

    private var player: MediaPlayer? = null
    private var session: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var resumeAfterFocus = false

    private val ticker = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && MusicState.playing) {
                try { MusicState.pos = p.currentPosition } catch (e: Exception) { }
                handler.postDelayed(this, 500)
            }
        }
    }

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (MusicState.playing) pause()   // headphone nikalne par gaana ruk jaye
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> { resumeAfterFocus = false; pause() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { resumeAfterFocus = MusicState.playing; pause(false) }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player?.setVolume(0.3f, 0.3f)
            AudioManager.AUDIOFOCUS_GAIN -> {
                player?.setVolume(1f, 1f)
                if (resumeAfterFocus) { resumeAfterFocus = false; play() }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onCreate() {
        super.onCreate()
        instance = this
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "Music", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        session = MediaSession(this, "AllInOneFM").apply {
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { play() }
                override fun onPause() { pause() }
                override fun onSkipToNext() { next() }
                override fun onSkipToPrevious() { prev() }
                override fun onSeekTo(pos: Long) { seekTo(pos.toInt()) }
                override fun onStop() { stopAll() }
            })
            isActive = true
        }
        ContextCompat.registerReceiver(
            this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startFg()
                val list = intent?.getStringArrayListExtra("list") ?: arrayListOf()
                MusicState.playlist = list
                playIndex(intent?.getIntExtra("index", 0) ?: 0)
            }
            ACTION_TOGGLE -> if (MusicState.playing) pause() else play()
            ACTION_NEXT -> next()
            ACTION_PREV -> prev()
            ACTION_STOP -> stopAll()
        }
        if (player == null && intent?.action != ACTION_START) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        try { unregisterReceiver(noisy) } catch (e: Exception) { }
        session?.release()
        MusicState.playing = false
        super.onDestroy()
    }

    // ---------------- controls (UI bhi inhe seedha bulata hai) ----------------

    fun playAt(i: Int) = playIndex(i)

    fun play() {
        val p = player ?: return
        if (MusicState.dur > 0 && MusicState.pos >= MusicState.dur - 300) p.seekTo(0)
        if (requestFocus()) {
            p.start()
            setPlaying(true)
        }
        updateAll()
    }

    fun pause(abandon: Boolean = true) {
        try { player?.pause() } catch (e: Exception) { }
        setPlaying(false)
        if (abandon) abandonFocus()
        updateAll()
    }

    fun toggle() { if (MusicState.playing) pause() else play() }

    fun next() {
        val n = nextIndex(false)
        if (n >= 0) playIndex(n)
    }

    fun prev() {
        val n = MusicState.playlist.size
        if (n == 0) return
        if (MusicState.pos > 3000) { seekTo(0); return }
        playIndex(if (MusicState.index > 0) MusicState.index - 1 else n - 1)
    }

    fun seekTo(ms: Int) {
        try { player?.seekTo(ms) } catch (e: Exception) { }
        MusicState.pos = ms
        updateAll()
    }

    fun stopAll() {
        releasePlayer()
        setPlaying(false)
        abandonFocus()
        MusicState.playlist = emptyList()
        MusicState.index = -1
        MusicState.pos = 0
        MusicState.dur = 0
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------------- andar ka kaam ----------------

    private fun playIndex(i: Int) {
        val list = MusicState.playlist
        if (i !in list.indices) return
        MusicState.index = i
        MusicState.error = null
        MusicState.pos = 0
        MusicState.dur = 0
        releasePlayer()
        val p = MediaPlayer()
        player = p
        try {
            p.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            p.setDataSource(list[i])
            p.setOnPreparedListener { mp ->
                MusicState.dur = mp.duration
                if (requestFocus()) {
                    mp.start()
                    setPlaying(true)
                }
                updateAll()
            }
            p.setOnCompletionListener { onComplete() }
            p.setOnErrorListener { _, _, _ ->
                MusicState.error = "Ye gaana chal nahi paya"
                setPlaying(false)
                updateAll()
                true
            }
            p.prepareAsync()
        } catch (e: Exception) {
            MusicState.error = "Ye gaana chal nahi paya"
        }
        setPlaying(false)
        updateAll()
    }

    private fun onComplete() {
        if (MusicState.repeat == 2) { playIndex(MusicState.index); return }
        val n = nextIndex(true)
        if (n >= 0) playIndex(n)
        else {
            setPlaying(false)
            MusicState.pos = 0
            try { player?.seekTo(0) } catch (e: Exception) { }
            updateAll()
        }
    }

    private fun nextIndex(auto: Boolean): Int {
        val n = MusicState.playlist.size
        if (n == 0) return -1
        val cur = MusicState.index
        if (MusicState.shuffle && n > 1) {
            var r = cur
            while (r == cur) r = (0 until n).random()
            return r
        }
        val nx = cur + 1
        return if (nx < n) nx else if (!auto || MusicState.repeat == 1) 0 else -1
    }

    private fun setPlaying(b: Boolean) {
        MusicState.playing = b
        handler.removeCallbacks(ticker)
        if (b) handler.post(ticker)
    }

    private fun releasePlayer() {
        player?.let {
            try { it.setOnCompletionListener(null); it.reset(); it.release() } catch (e: Exception) { }
        }
        player = null
    }

    @Suppress("DEPRECATION")
    private fun requestFocus(): Boolean {
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        return r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    @Suppress("DEPRECATION")
    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            audioManager.abandonAudioFocus(focusListener)
        }
    }

    // ---------------- notification + media session ----------------

    private fun startFg() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIF_ID, n)
    }

    private fun updateAll() {
        updateSession()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification())
    }

    private fun updateSession() {
        val s = session ?: return
        val state = if (MusicState.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or
                PlaybackState.ACTION_STOP
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(state, MusicState.pos.toLong(), if (MusicState.playing) 1f else 0f)
                .build()
        )
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, MusicState.current?.let { File(it).name } ?: "")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, MusicState.dur.toLong())
                .build()
        )
    }

    private fun action(icon: Int, title: String, act: String): Notification.Action {
        val pi = PendingIntent.getService(
            this, act.hashCode(),
            Intent(this, MusicService::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(Icon.createWithResource(this, icon), title, pi).build()
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val cur = MusicState.current
        val title = cur?.let { File(it).name } ?: "All-In-One FM"
        val text = if (MusicState.playlist.isNotEmpty())
            "${MusicState.index + 1} / ${MusicState.playlist.size}" else ""
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MediaActivity::class.java).putExtra("path", cur ?: ""),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL)
        else Notification.Builder(this)
        b.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(MusicState.playing)
        b.addAction(action(android.R.drawable.ic_media_previous, "Prev", ACTION_PREV))
        if (MusicState.playing) b.addAction(action(android.R.drawable.ic_media_pause, "Pause", ACTION_TOGGLE))
        else b.addAction(action(android.R.drawable.ic_media_play, "Play", ACTION_TOGGLE))
        b.addAction(action(android.R.drawable.ic_media_next, "Next", ACTION_NEXT))
        b.addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Stop", ACTION_STOP))
        b.setStyle(
            Notification.MediaStyle()
                .setMediaSession(session?.sessionToken)
                .setShowActionsInCompactView(0, 1, 2)
        )
        return b.build()
    }
}
