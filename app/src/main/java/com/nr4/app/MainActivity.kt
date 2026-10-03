package com.nr4.app

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class Channel(
    val name: String,
    val url: String,
    val group: String,
    val userAgent: String = "",
    val catchupSource: String = "",
    val catchupDays: Int = 0
)

class MainActivity : AppCompatActivity() {

    // ── Плеер ────────────────────────────────────────────
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var titleView: TextView
    private lateinit var infoView: TextView
    private lateinit var clockView: TextView
    private lateinit var topBar: View

    // ── Список каналов (bottom sheet) ────────────────────
    private lateinit var bottomSheet: View
    private lateinit var list: RecyclerView
    private lateinit var searchInput: EditText
    private lateinit var chipsWrap: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var hintList: TextView
    private var adapter: ChannelAdapter? = null

    private var allChannels: List<Channel> = emptyList()
    private var visibleChannels: List<Channel> = emptyList()
    private var groupsList: List<String> = emptyList()
    private var currentGroupIndex: Int = 0
    private var searchQuery: String = ""

    // ── Состояние ────────────────────────────────────────
    private var sheetVisible = false
    private var loadedFrom: String = ""
    private var currentIndex: Int = 0

    // ── Таймеры ──────────────────────────────────────────
    private val clockHandler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val clockTick = object : Runnable {
        override fun run() {
            clockView.text = timeFmt.format(Date())
            clockHandler.postDelayed(this, 1000)
        }
    }

    private val IDLE_TIMEOUT_MS = 30L * 60L * 1000L
    private val IDLE_CHECK_MS = 30_000L
    private var lastActivityTime: Long = System.currentTimeMillis()
    private var backgroundedAt: Long = 0L
    private val idleCheck = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() - lastActivityTime >= IDLE_TIMEOUT_MS) {
                exitApp()
                return
            }
            clockHandler.postDelayed(this, IDLE_CHECK_MS)
        }
    }

    // ── Свайпы ───────────────────────────────────────────
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L

    private fun builtInUrl(): String =
        "https" + "://" + "gvineon550-coder" + ".github.io/" + "8Z6evf3ezzM469" + "/all_checked.m3u8"

    private fun activeSource(): String {
        val a = AppPrefs.getActive(this)
        return a.ifEmpty { builtInUrl() }
    }

    private fun exitApp() {
        try { player?.release(); player = null } catch (_: Exception) {}
        finishAffinity()
        Runtime.getRuntime().exit(0)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        playerView = findViewById(R.id.player)
        titleView = findViewById(R.id.title)
        infoView = findViewById(R.id.stream_info)
        clockView = findViewById(R.id.clock)
        topBar = findViewById(R.id.top_bar)
        bottomSheet = findViewById(R.id.bottom_sheet)
        hintList = findViewById(R.id.hint_list)
        list = findViewById(R.id.list)
        searchInput = findViewById(R.id.search)
        chipsWrap = findViewById(R.id.chips)
        statusView = findViewById(R.id.status)

        list.layoutManager = LinearLayoutManager(this)
        adapter = ChannelAdapter(emptyList()) { idx -> selectChannel(idx) }
        list.adapter = adapter

        // Кнопки
        findViewById<View>(R.id.btn_settings).setOnClickListener {
            lastActivityTime = System.currentTimeMillis()
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.btn_refresh).setOnClickListener {
            lastActivityTime = System.currentTimeMillis()
            val src = activeSource()
            loadedFrom = src
            Toast.makeText(this, "Обновляю...", Toast.LENGTH_SHORT).show()
            reload(src)
        }

        // Поиск
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                lastActivityTime = System.currentTimeMillis()
                searchQuery = s?.toString()?.trim()?.lowercase() ?: ""
                applyFilter()
            }
        })

        // Клик по подсказке — открыть список
        hintList.setOnClickListener { showSheet() }
        bottomSheet.setOnClickListener { /* пустой клик, чтобы не закрывалось */ }

        // Свайпы по всему экрану
        playerView.setOnTouchListener { v, e -> handleTouch(v, e) }
        bottomSheet.setOnTouchListener { v, e -> handleSheetTouch(v, e) }

        // Загружаем плейлист
        val src = activeSource()
        loadedFrom = src
        reload(src)

        clockHandler.post(clockTick)
        clockHandler.post(idleCheck)
        scheduleHideBar()
    }

    // ── Обработка свайпов по плееру ──────────────────────
    @SuppressLint("ClickableViewAccessibility")
    private fun handleTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; downT = System.currentTimeMillis()
                lastActivityTime = System.currentTimeMillis()
                return false
            }
            MotionEvent.ACTION_UP -> {
                val dx = e.x - downX
                val dy = e.y - downY
                val dt = System.currentTimeMillis() - downT
                lastActivityTime = System.currentTimeMillis()

                if (abs(dy) > 100 && abs(dy) > abs(dx) * 1.5) {
                    if (dy > 0) {
                        // Свайп вниз — открыть список
                        showSheet()
                    } else {
                        // Свайп вверх — закрыть
                        hideSheet()
                    }
                    v.performClick()
                    return true
                }
                if (abs(dx) > 80 && abs(dx) > abs(dy) * 1.5) {
                    // Свайп влево/вправо — переключение каналов
                    if (dx < 0) switchChannel(1) else switchChannel(-1)
                    v.performClick()
                    return true
                }
                if (abs(dx) < 20 && abs(dy) < 20 && dt < 300) {
                    toggleBar()
                    v.performClick()
                    return true
                }
                return false
            }
        }
        return false
    }

    // ── Свайпы по bottom sheet ───────────────────────────
    @SuppressLint("ClickableViewAccessibility")
    private fun handleSheetTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; downT = System.currentTimeMillis()
                lastActivityTime = System.currentTimeMillis()
                return false
            }
            MotionEvent.ACTION_UP -> {
                val dx = e.x - downX
                val dy = e.y - downY
                lastActivityTime = System.currentTimeMillis()

                if (abs(dy) > 100 && abs(dy) > abs(dx) * 1.5 && dy > 0) {
                    // Свайп вниз по шторке — закрыть
                    hideSheet()
                    return true
                }
                if (abs(dx) > 100 && abs(dx) > abs(dy) * 1.5) {
                    // Свайп влево/вправо — переключение групп
                    if (dx < 0) nextGroup() else prevGroup()
                    return true
                }
                return false
            }
        }
        return false
    }

    // ── Управление кнопками пульта ───────────────────────
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        lastActivityTime = System.currentTimeMillis()
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!sheetVisible) {
                    showSheet()
                    return true
                }
                return super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (sheetVisible) {
                    hideSheet()
                    return true
                }
                return super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (sheetVisible) {
                    prevGroup()
                    return true
                }
                switchChannel(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (sheetVisible) {
                    nextGroup()
                    return true
                }
                switchChannel(1)
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (sheetVisible) {
                    hideSheet()
                    return true
                }
                return super.onKeyDown(keyCode, event)
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ── Bottom sheet ─────────────────────────────────────
    private fun showSheet() {
        if (sheetVisible) return
        sheetVisible = true
        bottomSheet.visibility = View.VISIBLE
        hintList.visibility = View.GONE
        topBar.visibility = View.GONE
        scheduleHideBar()
    }

    private fun hideSheet() {
        if (!sheetVisible) return
        sheetVisible = false
        bottomSheet.visibility = View.GONE
        hintList.visibility = View.VISIBLE
        topBar.visibility = View.VISIBLE
        scheduleHideBar()
    }

    private fun toggleBar() {
        if (topBar.visibility == View.VISIBLE) {
            topBar.visibility = View.GONE
        } else {
            topBar.visibility = View.VISIBLE
            scheduleHideBar()
        }
    }

    private var barHideRunnable: Runnable? = null
    private fun scheduleHideBar() {
        barHideRunnable?.let { clockHandler.removeCallbacks(it) }
        barHideRunnable = Runnable {
            if (!sheetVisible) topBar.visibility = View.GONE
        }
        clockHandler.postDelayed(barHideRunnable!!, 5000)
    }

    private fun hideSystemBars() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    // ── Загрузка плейлиста ───────────────────────────────
    private fun reload(src: String) {
        statusView.text = "Загрузка плейлиста..."
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val channels = loadPlaylist(src)
                withContext(Dispatchers.Main) {
                    allChannels = channels
                    groupsList = buildGroups(channels)
                    currentGroupIndex = 0
                    searchQuery = ""
                    searchInput.setText("")
                    renderChips()
                    applyFilter()
                    // Автоматически играем первый канал
                    if (visibleChannels.isNotEmpty()) {
                        currentIndex = 0
                        play(0)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusView.text = "Ошибка: ${e.message}"
                }
            }
        }
    }

    private fun loadPlaylist(src: String): List<Channel> {
        val text = if (src.startsWith("/") || src.startsWith("file:")) {
            val f = File(src.replaceFirst("file://", ""))
            if (f.exists()) f.readText() else ""
        } else {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder().url(src).build()
            client.newCall(req).execute().body?.string() ?: ""
        }
        return parseM3U(text)
    }

    private fun parseM3U(text: String): List<Channel> {
        val out = mutableListOf<Channel>()
        var name: String? = null
        var group = ""
        var catchupSource = ""
        var catchupDays = 0
        var pendingUA = ""
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("#EXTINF")) {
                val comma = line.lastIndexOf(',')
                if (comma > 0) name = line.substring(comma + 1).trim()
                group = Regex("group-title=\"([^\"]*)\"").find(line)?.groupValues?.get(1) ?: ""
                catchupSource = Regex("catchup-source=\"([^\"]*)\"").find(line)?.groupValues?.get(1) ?: ""
                catchupDays = Regex("catchup-days=\"(\\d+)\"").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            } else if (line.startsWith("#EXTVLCOPT:http-user-agent=")) {
                pendingUA = line.substringAfter("=").trim()
            } else if (line.isNotEmpty() && !line.startsWith("#") && name != null) {
                out.add(Channel(name!!, line, group, pendingUA, catchupSource, catchupDays))
                name = null; pendingUA = ""; catchupSource = ""; catchupDays = 0
            }
        }
        return out
    }

    // ── Группы ───────────────────────────────────────────
    private fun buildGroups(channels: List<Channel>): List<String> {
        val groups = linkedMapOf<String, Int>()
        groups["Все"] = channels.size
        for (c in channels) {
            val g = c.group.ifEmpty { "Без группы" }
            groups[g] = (groups[g] ?: 0) + 1
        }
        return groups.keys.toList()
    }

    private fun renderChips() {
        chipsWrap.removeAllViews()
        val accent = 0xFF60a5fa.toInt()
        val muted = 0xFF8a8f98.toInt()
        val dark = 0xFF0f1115.toInt()
        val border = 0xFF23272e.toInt()

        for ((idx, g) in groupsList.withIndex()) {
            val isActive = idx == currentGroupIndex
            val count = if (g == "Все") allChannels.size else allChannels.count { it.group.ifEmpty { "Без группы" } == g }
            val chip = TextView(this).apply {
                text = "$g ($count)"
                textSize = 13f
                setPadding(28, 14, 28, 14)
                setTextColor(if (isActive) dark else muted)
                setBackgroundColor(if (isActive) accent else border)
                setOnClickListener {
                    lastActivityTime = System.currentTimeMillis()
                    currentGroupIndex = idx
                    renderChips()
                    applyFilter()
                }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.marginEnd = 12
            chip.layoutParams = params
            chipsWrap.addView(chip)
        }
    }

    private fun nextGroup() {
        if (groupsList.isEmpty()) return
        currentGroupIndex = (currentGroupIndex + 1) % groupsList.size
        renderChips()
        applyFilter()
    }

    private fun prevGroup() {
        if (groupsList.isEmpty()) return
        currentGroupIndex = if (currentGroupIndex - 1 < 0) groupsList.size - 1 else currentGroupIndex - 1
        renderChips()
        applyFilter()
    }

    private fun applyFilter() {
        val currentGroup = groupsList.getOrNull(currentGroupIndex)
        var list = if (currentGroup == null || currentGroup == "Все") allChannels
        else allChannels.filter { it.group.ifEmpty { "Без группы" } == currentGroup }
        if (searchQuery.isNotEmpty()) list = list.filter { it.name.lowercase().contains(searchQuery) }
        visibleChannels = list
        statusView.text = "Каналов: ${list.size}"
        adapter?.update(list)

        // Если текущий канал не в этом списке — переключить на первый
        if (visibleChannels.isNotEmpty() && (currentIndex !in visibleChannels.indices || !visibleChannels.contains(visibleChannels.getOrNull(currentIndex)))) {
            currentIndex = 0
            play(0)
        }
    }

    // ── Плеер ────────────────────────────────────────────
    private fun play(index: Int) {
        if (index !in visibleChannels.indices) return
        currentIndex = index
        val ch = visibleChannels[index]

        titleView.text = if (ch.group.isNotEmpty()) "${ch.name}  ·  ${ch.group}" else ch.name
        lastActivityTime = System.currentTimeMillis()

        val useUa = ch.userAgent.ifEmpty { "IPTV/1.0" }
        val ds = DefaultHttpDataSource.Factory()
            .setUserAgent(useUa)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)

        player?.release()
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(ds))
            .build()
        playerView.player = player
        attachAnalytics()

        val b = MediaItem.Builder().setUri(ch.url)
        if (ch.url.contains(".m3u8") || ch.url.contains("kinowalk.hopto.org")) {
            b.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        player?.apply {
            setMediaItem(b.build())
            prepare()
            playWhenReady = true
        }
    }

    private fun selectChannel(idx: Int) {
        lastActivityTime = System.currentTimeMillis()
        play(idx)
        hideSheet()
    }

    private fun switchChannel(delta: Int) {
        if (visibleChannels.isEmpty()) return
        var idx = currentIndex + delta
        if (idx < 0) idx = visibleChannels.size - 1
        if (idx >= visibleChannels.size) idx = 0
        play(idx)
    }

    private fun attachAnalytics() {
        player?.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?
            ) { updateStreamInfo(format) }
        })
    }

    private fun updateStreamInfo(format: Format) {
        val w = format.width; val h = format.height
        val bitrate = format.bitrate
        val codec = format.codecs ?: format.sampleMimeType ?: "?"
        val res = if (w > 0 && h > 0) "${w}×${h}" else "?"
        val br = if (bitrate > 0) "${bitrate / 1000} kbps" else "?"
        val cd = codec.substringBefore('.').uppercase().replace("VIDEO/", "").replace("AUDIO/", "")
        infoView.text = "$res · $br · $cd"
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        lastActivityTime = System.currentTimeMillis()
        clockHandler.removeCallbacks(idleCheck)
        clockHandler.post(idleCheck)

        if (backgroundedAt > 0) {
            if (System.currentTimeMillis() - backgroundedAt >= IDLE_TIMEOUT_MS) {
                exitApp()
                return
            }
            backgroundedAt = 0L
        }

        val src = activeSource()
        if (src != loadedFrom) {
            loadedFrom = src
            reload(src)
        }
    }

    override fun onPause() {
        super.onPause()
        clockHandler.removeCallbacks(idleCheck)
        backgroundedAt = System.currentTimeMillis()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        clockHandler.removeCallbacks(clockTick)
        clockHandler.removeCallbacks(idleCheck)
        barHideRunnable?.let { clockHandler.removeCallbacks(it) }
        player?.release(); player = null
    }
}

class ChannelAdapter(
    private var items: List<Channel>,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.VH>() {
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.ch_name)
        val group: TextView = v.findViewById(R.id.ch_group)
    }
    fun update(newItems: List<Channel>) { items = newItems; notifyDataSetChanged() }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
        return VH(v)
    }
    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = items[position]
        holder.name.text = ch.name
        holder.group.text = ch.group
        holder.itemView.setOnClickListener { onClick(position) }
    }
    override fun getItemCount() = items.size
}
