package com.nr4.app

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
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
    private lateinit var titleView: TextView
    private lateinit var infoView: TextView
    private lateinit var clockView: TextView
    private lateinit var topBar: View

    private var urls: ArrayList<String> = arrayListOf()
    private var names: ArrayList<String> = arrayListOf()
    private var groups: ArrayList<String> = arrayListOf()
    private var uas: ArrayList<String> = arrayListOf()
    private var catchups: ArrayList<String> = arrayListOf()
    private var catchupDaysArr: ArrayList<Int> = arrayListOf()
    private var currentIndex: Int = 0

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

        urls = intent.getStringArrayListExtra("urls") ?: arrayListOf()
        names = intent.getStringArrayListExtra("names") ?: arrayListOf()
        groups = intent.getStringArrayListExtra("groups") ?: arrayListOf()
        uas = intent.getStringArrayListExtra("uas") ?: arrayListOf()
        catchups = intent.getStringArrayListExtra("catchups") ?: arrayListOf()
        catchupDaysArr = intent.getIntegerArrayListExtra("catchupDays") ?: arrayListOf()
        currentIndex = intent.getIntExtra("index", 0)

        titleView = findViewById(R.id.title)
        infoView = findViewById(R.id.stream_info)
        clockView = findViewById(R.id.clock)
        topBar = findViewById(R.id.top_bar)

        titleView.setOnLongClickListener {
            showArchiveDialog()
            true
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        player = ExoPlayer.Builder(this).build()
        val pv = findViewById<PlayerView>(R.id.player)
        pv.player = player

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

        val btnPrev = findViewById<ImageButton>(R.id.exo_rew)
        btnPrev.setOnClickListener { switchChannel(-1) }

        val btnNext = findViewById<ImageButton>(R.id.exo_ffwd)
        btnNext.setOnClickListener { switchChannel(1) }

        if (urls.isNotEmpty() && currentIndex in urls.indices) {
            play(currentIndex)
        }
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

    private fun switchChannel(delta: Int) {
        if (urls.isEmpty()) return
        var idx = currentIndex + delta
        if (idx < 0) idx = urls.size - 1
        if (idx >= urls.size) idx = 0
        play(idx)
    }

    private fun play(index: Int) {
        if (index !in urls.indices) return
        currentIndex = index
        val url = urls[index]
        val name = names.getOrNull(index) ?: ""
        val group = groups.getOrNull(index) ?: ""
        val ua = uas.getOrNull(index) ?: ""

        titleView.text = if (group.isNotEmpty()) "$name  ·  $group" else name

        val useUa = ua.ifEmpty { "IPTV/1.0" }

        val dsFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(useUa)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)

        player?.release()
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

        val itemBuilder = MediaItem.Builder().setUri(url)
        if (url.contains(".m3u8") || url.contains("kinowalk.hopto.org")) {
            itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        player?.apply {
            setMediaItem(itemBuilder.build())
            prepare()
            playWhenReady = true
        }
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

    private fun buildArchiveUrl(secondsAgo: Int): String? {
        val cs = catchups.getOrNull(currentIndex) ?: return null
        val live = urls.getOrNull(currentIndex) ?: return null
        if (cs.isEmpty()) return null
        val params = cs.replace("\${offset}", secondsAgo.toString())
        return if (live.contains("?")) {
            live + "&" + params.removePrefix("?")
        } else {
            if (params.startsWith("?")) live + params
            else live + "?" + params
        }
    }

    private fun showArchiveDialog() {
        val days = catchupDaysArr.getOrNull(currentIndex) ?: 0
        val cs = catchups.getOrNull(currentIndex) ?: ""

        if (days <= 0 || cs.isEmpty()) {
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
        ).filter { it.first <= days * 24 }

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
                    val name = names.getOrNull(currentIndex) ?: ""
                    titleView.text = "$name · архив $hours ч назад"
                    val currentUa = uas.getOrNull(currentIndex) ?: ""
                    val ds = DefaultHttpDataSource.Factory()
                        .setUserAgent(currentUa.ifEmpty { "IPTV/1.0" })
                        .setAllowCrossProtocolRedirects(true)
                    player?.release()
                    player = ExoPlayer.Builder(this)
                        .setMediaSourceFactory(DefaultMediaSourceFactory(ds))
                        .build()
                    findViewById<PlayerView>(R.id.player).player = player
                    player?.apply {
                        setMediaItem(MediaItem.fromUri(archiveUrl))
                        prepare()
                        playWhenReady = true
                    }
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
