package com.nr4.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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
    private var currentGroup: String? = null
    private var adapter: ChannelAdapter? = null

    // Ссылка на плейлист собирается из частей в рантайме.
    // Прямая строка URL в коде не хранится.
    private fun decodePlaylistUrl(): String {
        val a = "https"
        val b = "://"
        val c = "gvineon550-coder"
        val d = ".github.io/"
        val e = "8Z6evf3ezzM469"
        val f = "/all_checked.m3u8"
        return a + b + c + d + e + f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val channels = loadPlaylist(decodePlaylistUrl())
                withContext(Dispatchers.Main) {
                    allChannels = channels
                    adapter = ChannelAdapter(emptyList()) { ch ->
                        val i = Intent(this@MainActivity, PlayerActivity::class.java)
                        i.putExtra("url", ch.url)
                        i.putExtra("name", ch.name)
                        i.putExtra("userAgent", ch.userAgent)
                        i.putExtra("catchupSource", ch.catchupSource)
                        i.putExtra("catchupDays", ch.catchupDays)
                        startActivity(i)
                    }
                    list.adapter = adapter
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

    private fun loadPlaylist(url: String): List<Channel> {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val req = Request.Builder().url(url).build()
        val body = client.newCall(req).execute().body?.string() ?: ""
        return parseM3U(body)
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

                val g = Regex("group-title=\"([^\"]*)\"").find(line)
                group = g?.groupValues?.get(1) ?: ""

                val cs = Regex("catchup-source=\"([^\"]*)\"").find(line)
                catchupSource = cs?.groupValues?.get(1) ?: ""

                val cd = Regex("catchup-days=\"(\\d+)\"").find(line)
                catchupDays = cd?.groupValues?.get(1)?.toIntOrNull() ?: 0
            } else if (line.startsWith("#EXTVLCOPT:http-user-agent=")) {
                pendingUA = line.substringAfter("=").trim()
            } else if (line.isNotEmpty() && !line.startsWith("#") && name != null) {
                out.add(Channel(name!!, line, group, pendingUA, catchupSource, catchupDays))
                name = null
                pendingUA = ""
                catchupSource = ""
                catchupDays = 0
            }
        }
        return out
    }

    private fun renderChips() {
        val wrap = findViewById<LinearLayout>(R.id.chips)
        wrap.removeAllViews()

        val accent = 0xFF60a5fa.toInt()
        val muted = 0xFF8a8f98.toInt()
        val dark = 0xFF0f1115.toInt()
        val border = 0xFF23272e.toInt()

        val groups = linkedMapOf<String, Int>()
        groups["Все"] = allChannels.size
        for (c in allChannels) {
            val g = c.group.ifEmpty { "Без группы" }
            groups[g] = (groups[g] ?: 0) + 1
        }

        for ((g, n) in groups) {
            val isActive = (g == "Все" && currentGroup == null) ||
                           (g == currentGroup)
            val chip = TextView(this).apply {
                text = "$g ($n)"
                textSize = 13f
                setPadding(28, 14, 28, 14)
                setTextColor(if (isActive) dark else muted)
                setBackgroundColor(if (isActive) accent else border)
                setOnClickListener {
                    currentGroup = if (g == "Все") null else g
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
            wrap.addView(chip)
        }
    }

    private fun applyFilter() {
        val filtered = if (currentGroup == null) allChannels
            else allChannels.filter { it.group.ifEmpty { "Без группы" } == currentGroup }
        findViewById<TextView>(R.id.status).text = "Каналов: ${filtered.size}"
        adapter?.update(filtered)
    }
}

class ChannelAdapter(
    private var items: List<Channel>,
    private val onClick: (Channel) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.ch_name)
        val group: TextView = v.findViewById(R.id.ch_group)
    }

    fun update(newItems: List<Channel>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = items[position]
        holder.name.text = ch.name
        holder.group.text = ch.group
        holder.itemView.setOnClickListener { onClick(ch) }
    }

    override fun getItemCount() = items.size
}
