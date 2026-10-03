package com.nr4.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

data class Channel(
    val name: String,
    val url: String,
    val group: String,
    val userAgent: String = "",
    val catchupSource: String = "",
    val catchupDays: Int = 0
)

class MainActivity : AppCompatActivity() {

    private var allChannels: List<Channel> = emptyList()
    private var visibleChannels: List<Channel> = emptyList()
    private var currentGroup: String? = null
    private var searchQuery: String = ""
    private var adapter: ChannelAdapter? = null
    private var loadedFrom: String = ""

    // ── Авто-выход при простое ───────────────────────────
    private val IDLE_TIMEOUT_MS = 30L * 60L * 1000L
    private val IDLE_CHECK_MS = 30_000L
    private var lastTouchTime: Long = System.currentTimeMillis()
    private var backgroundedAt: Long = 0L

    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleCheck = object : Runnable {
        override fun run() {
            val idle = System.currentTimeMillis() - lastTouchTime
            if (idle >= IDLE_TIMEOUT_MS) {
                exitApp()
                return
            }
            idleHandler.postDelayed(this, IDLE_CHECK_MS)
        }
    }
    // ─────────────────────────────────────────────────────

    private fun builtInUrl(): String =
        "https" + "://" + "gvineon550-coder" + ".github.io/" + "8Z6evf3ezzM469" + "/all_checked.m3u8"

    private fun activeSource(): String {
        val a = AppPrefs.getActive(this)
        return a.ifEmpty { builtInUrl() }
    }

    private fun exitApp() {
        finishAffinity()
        Runtime.getRuntime().exit(0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)

        findViewById<View>(R.id.btn_settings).setOnClickListener {
            lastTouchTime = System.currentTimeMillis()
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<View>(R.id.btn_refresh).setOnClickListener {
            lastTouchTime = System.currentTimeMillis()
            val src = activeSource()
            loadedFrom = src
            Toast.makeText(this, "Обновляю...", Toast.LENGTH_SHORT).show()
            reload(src)
        }

        val searchInput = findViewById<EditText>(R.id.search)
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                lastTouchTime = System.currentTimeMillis()
                searchQuery = s?.toString()?.trim()?.lowercase() ?: ""
                applyFilter()
            }
        })

        // Отслеживаем касания по всему экрану
        val root = findViewById<View>(android.R.id.content)
        root.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                lastTouchTime = System.currentTimeMillis()
            }
            false
        }

        adapter = ChannelAdapter(emptyList()) { idx ->
            lastTouchTime = System.currentTimeMillis()
            openPlayer(idx)
        }
        list.adapter = adapter
    }

    override fun onResume() {
        super.onResume()

        // Проверяем: если были в фоне долго — закрываемся
        if (backgroundedAt > 0) {
            val away = System.currentTimeMillis() - backgroundedAt
            if (away >= IDLE_TIMEOUT_MS) {
                exitApp()
                return
            }
            backgroundedAt = 0L
        }

        lastTouchTime = System.currentTimeMillis()
        idleHandler.removeCallbacks(idleCheck)
        idleHandler.post(idleCheck)

        val src = activeSource()
        if (src != loadedFrom) {
            loadedFrom = src
            reload(src)
        }
    }

    override fun onPause() {
        super.onPause()
        idleHandler.removeCallbacks(idleCheck)
        backgroundedAt = System.currentTimeMillis()
    }

    override fun onDestroy() {
        super.onDestroy()
        idleHandler.removeCallbacks(idleCheck)
    }

    private fun reload(src: String) {
        findViewById<TextView>(R.id.status).text = "Загрузка плейлиста..."
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val channels = loadPlaylist(src)
                withContext(Dispatchers.Main) {
                    allChannels = channels
                    currentGroup = null
                    renderChips()
                    applyFilter()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    findViewById<TextView>(R.id.status).text = "Ошибка: ${e.message}"
                }
            }
        }
    }

    private fun openPlayer(index: Int) {
        val urls = ArrayList<String>(visibleChannels.size)
        val names = ArrayList<String>(visibleChannels.size)
        val groups = ArrayList<String>(visibleChannels.size)
        val uas = ArrayList<String>(visibleChannels.size)
        val catchups = ArrayList<String>(visibleChannels.size)
        val catchupDaysArr = ArrayList<Int>(visibleChannels.size)
        for (c in visibleChannels) {
            urls.add(c.url); names.add(c.name); groups.add(c.group)
            uas.add(c.userAgent); catchups.add(c.catchupSource); catchupDaysArr.add(c.catchupDays)
        }
        val i = Intent(this, PlayerActivity::class.java)
        i.putStringArrayListExtra("urls", urls)
        i.putStringArrayListExtra("names", names)
        i.putStringArrayListExtra("groups", groups)
        i.putStringArrayListExtra("uas", uas)
        i.putStringArrayListExtra("catchups", catchups)
        i.putIntegerArrayListExtra("catchupDays", catchupDaysArr)
        i.putExtra("index", index)
        startActivity(i)
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

    private fun renderChips() {
        val wrap = findViewById<LinearLayout>(R.id.chips)
        wrap.removeAllViews()
        val accent = 0xFF60a5fa.toInt(); val muted = 0xFF8a8f98.toInt()
        val dark = 0xFF0f1115.toInt(); val border = 0xFF23272e.toInt()
        val groups = linkedMapOf<String, Int>()
        groups["Все"] = allChannels.size
        for (c in allChannels) {
            val g = c.group.ifEmpty { "Без группы" }
            groups[g] = (groups[g] ?: 0) + 1
        }
        for ((g, n) in groups) {
            val isActive = (g == "Все" && currentGroup == null) || (g == currentGroup)
            val chip = TextView(this).apply {
                text = "$g ($n)"
                textSize = 13f
                setPadding(28, 14, 28, 14)
                setTextColor(if (isActive) dark else muted)
                setBackgroundColor(if (isActive) accent else border)
                setOnClickListener {
                    lastTouchTime = System.currentTimeMillis()
                    currentGroup = if (g == "Все") null else g
                    renderChips(); applyFilter()
                }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.marginEnd = 12
            chip.layoutParams = params
            wrap.addView(chip)
        }
    }

    private fun applyFilter() {
        var list = if (currentGroup == null) allChannels
            else allChannels.filter { it.group.ifEmpty { "Без группы" } == currentGroup }
        if (searchQuery.isNotEmpty()) list = list.filter { it.name.lowercase().contains(searchQuery) }
        visibleChannels = list
        findViewById<TextView>(R.id.status).text = "Каналов: ${list.size}"
        adapter?.update(list)
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
