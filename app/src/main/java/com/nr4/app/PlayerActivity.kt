package com.nr4.app

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private var liveUrl: String = ""
    private var channelName: String = ""
    private var userAgent: String = ""
    private var catchupSource: String = ""
    private var catchupDays: Int = 0

    private lateinit var titleView: TextView
    private lateinit var infoView: TextView
    private lateinit var clockView: TextView
    private lateinit var topBar: View

    private val clockHandler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val clockTick = object : Runnable {
        override fun run() {
            clockView.text = timeFmt.format(Date())
            clockHandler.postDelayed(this, 1000)
        }
    }

    private var barVisible = true
    private val hideBarRunnable = Runnable {
        topBar.visibility = View.GONE
        barVisible = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        liveUrl = intent.getStringExtra("url") ?: return
        channelName = intent.getStringExtra("name") ?: ""
        userAgent = intent.getStringExtra("userAgent") ?: ""
        catchupSource = intent.getStringExtra("catchupSource") ?: ""
        catchupDays = intent.getIntExtra("catchupDays", 0)

        titleView = findViewById(R.id.title)
        infoView = findViewById(R.id.stream_info)
        clockView = findViewById(R.id.clock)
        topBar = findViewById(R.id.top_bar)

        titleView.text = channelName
        titleView.setOnLongClickListener {
            showArchiveDialog()
            true
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        val isKinowalk = liveUrl.contains("kinowalk.hopto.org")

        val ua = when {
            userAgent.isNotEmpty() -> userAgent
            isKinowalk -> "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 " +
                          "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            else -> "IPTV/1.0"
        }

        val headers = mutableMapOf<String, String>()
        if (isKinowalk) {
            headers["Referer"] = "https://vk.com/"
            headers["Origin"] = "https://vk.com"
        }

        val dsFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dsFactory))
            .build()
        findViewById<PlayerView>(R.id.player).player = player

        player?.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?
            ) {
                updateStreamInfo(format)
            }
        })

        findViewById<View>(R.id.player).setOnClickListener {
            if (barVisible) {
                topBar.visibility = View.GONE
                barVisible = false
                clockHandler.removeCallbacks(hideBarRunnable)
            } else {
                topBar.visibility = View.VISIBLE
                barVisible = true
                scheduleHideBar()
            }
        }

        play(liveUrl)
        clockHandler.post(clockTick)
        scheduleHideBar()
    }

    private fun scheduleHideBar() {
        clockHandler.removeCallbacks(hideBarRunnable)
        clockHandler.postDelayed(hideBarRunnable, 5000)
    }

    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun updateStreamInfo(format: Format) {
        val w = format.width
        val h = format.height
        val bitrate = format.bitrate
        val codec = format.codecs ?: format.sampleMimeType ?: "?"

        val res = if (w > 0 && h > 0) "${w}×${h}" else "?"
        val br = if (bitrate > 0) "${bitrate / 1000} kbps" else "?"
        val cd = codec.substringBefore('.').uppercase()
            .replace("VIDEO/", "").replace("AUDIO/", "")

        infoView.text = "$res · $br · $cd"
    }

    private fun play(url: String) {
        val isKinowalk = url.contains("kinowalk.hopto.org")

        val itemBuilder = MediaItem.Builder().setUri(url)
        if (isKinowalk || url.contains(".m3u8")) {
            itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        val item = itemBuilder.build()

        player?.apply {
            stop()
            clearMediaItems()
            setMediaItem(item)
            prepare()
            playWhenReady = true
        }
    }

    private fun buildArchiveUrl(secondsAgo: Int): String? {
        if (catchupSource.isEmpty()) return null
        val params = catchupSource.replace("\${offset}", secondsAgo.toString())
        return if (liveUrl.contains("?")) {
            liveUrl + "&" + params.removePrefix("?")
        } else {
            if (params.startsWith("?")) liveUrl + params
            else liveUrl + "?" + params
        }
    }

    private fun showArchiveDialog() {
        if (catchupDays <= 0 || catchupSource.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Архив недоступен")
                .setMessage("Для этого канала архив не поддерживается.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val options = listOf(
            1 to "1 час назад",
            2 to "2 часа назад",
            3 to "3 часа назад",
            6 to "6 часов назад",
            12 to "12 часов назад",
            24 to "1 день назад",
            48 to "2 дня назад"
        ).filter { it.first <= catchupDays * 24 }

        if (options.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Архив недоступен")
                .setMessage("Для этого канала архив не поддерживается.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Смотреть из архива")
            .setItems(options.map { it.second }.toTypedArray()) { _, which ->
                val hours = options[which].first
                val archiveUrl = buildArchiveUrl(hours * 3600)
                if (archiveUrl != null) {
                    titleView.text = "$channelName · архив $hours ч назад"
                    play(archiveUrl)
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onDestroy() {
        super.onDestroy()
        clockHandler.removeCallbacks(clockTick)
        clockHandler.removeCallbacks(hideBarRunnable)
        player?.release()
        player = null
    }
}
