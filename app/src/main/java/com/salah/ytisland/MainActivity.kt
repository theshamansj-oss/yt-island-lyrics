package com.salah.ytisland

import android.app.*
import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.text.TextUtils
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 120, 64, 64)
            setBackgroundColor(Color.parseColor("#0F0F0F"))
        }

        val title = TextView(this).apply {
            text = "YT Dynamic Island Lyrics"
            textSize = 24f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        }

        val btnOverlay = Button(this).apply {
            text = "1. Grant Overlay Permission"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }

        val btnNotif = Button(this).apply {
            text = "2. Grant Media Notification Access"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }

        val btnStart = Button(this).apply {
            text = "3. Launch Dynamic Island"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "Grant Overlay permission first!", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                startForegroundService(Intent(this@MainActivity, IslandOverlayService::class.java))
                Toast.makeText(this@MainActivity, "Dynamic Island Active! Open YouTube.", Toast.LENGTH_LONG).show()
            }
        }

        layout.addView(title)
        layout.addView(btnOverlay)
        layout.addView(btnNotif)
        layout.addView(btnStart)
        setContentView(layout)
    }
}

data class LyricLine(val timeMs: Long, val text: String)

object LyricsState {
    var currentTitle = ""
    var currentArtist = ""
    var positionMs = 0L
    var isPlaying = false
    var lines: List<LyricLine> = emptyList()
    var statusText = "Waiting for YouTube..."
}

class MediaMonitorService : NotificationListenerService() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastFetchedKey = ""

    override fun onListenerConnected() {
        val mgr = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(this, MediaMonitorService::class.java)
        scope.launch {
            while (isActive) {
                try {
                    val sessions = mgr.getActiveSessions(component)
                    val yt = sessions.firstOrNull {
                        it.packageName.contains("youtube", ignoreCase = true) ||
                        it.playbackState?.state == PlaybackState.STATE_PLAYING
                    }
                    if (yt != null) {
                        val meta = yt.metadata
                        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
                        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
                        LyricsState.currentTitle = title
                        LyricsState.currentArtist = artist
                        LyricsState.positionMs = yt.playbackState?.position ?: 0L
                        LyricsState.isPlaying = yt.playbackState?.state == PlaybackState.STATE_PLAYING

                        val key = "$title|$artist"
                        if (title.isNotBlank() && key != lastFetchedKey) {
                            lastFetchedKey = key
                            LyricsState.statusText = "Fetching lyrics: $title..."
                            LyricsState.lines = fetchSyncedLyrics(title, artist)
                            if (LyricsState.lines.isEmpty()) {
                                LyricsState.statusText = "♪ $title — $artist (No synced lyrics)"
                            }
                        }
                    } else {
                        LyricsState.isPlaying = false
                    }
                } catch (_: Exception) {}
                delay(400)
            }
        }
    }

    private fun fetchSyncedLyrics(rawTitle: String, rawArtist: String): List<LyricLine> {
        val cleanTitle = rawTitle.replace(Regex("\\(.*?\\)|\\[.*?\\]|Official.*", RegexOption.IGNORE_CASE), "").trim()
        val q = URLEncoder.encode("$cleanTitle $rawArtist".trim(), "UTF-8")
        return try {
            val conn = URL("https://lrclib.net/api/search?q=$q").openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "YTIslandLyrics/1.0")
            val body = conn.inputStream.bufferedReader().readText()
            val arr = org.json.JSONArray(body)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val synced = obj.optString("syncedLyrics", "")
                if (synced.isNotBlank()) return parseLrc(synced)
            }
            emptyList()
        } catch (_: Exception) { emptyList() }
    }

    private fun parseLrc(lrc: String): List<LyricLine> {
        val regex = Regex("\\[(\\d+):(\\d+\\.\\d+)\\](.*)")
        return lrc.lines().mapNotNull { line ->
            val m = regex.find(line) ?: return@mapNotNull null
            val mins = m.groupValues[1].toLong()
            val secs = (m.groupValues[2].toDouble() * 1000).toLong()
            val txt = m.groupValues[3].trim()
            if (txt.isEmpty()) null else LyricLine(mins * 60_000 + secs, txt)
        }
    }
}

class IslandOverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var pillContainer: LinearLayout
    private lateinit var pillText: TextView
    private lateinit var fullTranscriptText: TextView
    private var expanded = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        startForegroundNotif()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val bg = GradientDrawable().apply {
            setColor(Color.parseColor("#EE0A0A0A"))
            cornerRadius = 64f
            setStroke(2, Color.parseColor("#33FFFFFF"))
        }

        pillContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            setPadding(42, 22, 42, 22)
        }

        pillText = TextView(this).apply {
            text = "● YT Island Ready"
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        fullTranscriptText = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#CCCCCC"))
            textSize = 12f
            setPadding(0, 18, 0, 8)
            visibility = View.GONE
            maxLines = 12
        }

        pillContainer.addView(pillText)
        pillContainer.addView(fullTranscriptText)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 28
        }

        pillContainer.setOnClickListener {
            expanded = !expanded
            fullTranscriptText.visibility = if (expanded) View.VISIBLE else View.GONE
            params.width = if (expanded) (resources.displayMetrics.widthPixels * 0.9).toInt() else WindowManager.LayoutParams.WRAP_CONTENT
            wm.updateViewLayout(pillContainer, params)
        }

        wm.addView(pillContainer, params)
        handler.post(updateLoop)
    }

    private val updateLoop = object : Runnable {
        override fun run() {
            val lines = LyricsState.lines
            if (lines.isNotEmpty()) {
                val idx = lines.indexOfLast { it.timeMs <= LyricsState.positionMs }.coerceAtLeast(0)
                pillText.text = "♪ ${lines[idx].text}"
                if (expanded) {
                    val window = lines.drop((idx - 2).coerceAtLeast(0)).take(10)
                    fullTranscriptText.text = window.joinToString("\n") {
                        if (it == lines[idx]) "▶ ${it.text}" else "   ${it.text}"
                    }
                }
            } else {
                pillText.text = LyricsState.statusText
                if (expanded) fullTranscriptText.text = "Play any song on YouTube or YT Music."
            }
            handler.postDelayed(this, 300)
        }
    }

    private fun startForegroundNotif() {
        val chId = "yt_island_ch"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(chId, "YT Island", NotificationManager.IMPORTANCE_LOW))
        val notif = Notification.Builder(this, chId)
            .setContentTitle("YT Dynamic Island Active")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        startForeground(1, notif)
    }

    override fun onBind(intent: Intent?) = null
}
