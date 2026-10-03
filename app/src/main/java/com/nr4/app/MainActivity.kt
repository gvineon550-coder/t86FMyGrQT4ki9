package com.nr4.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
    val catchupSource: String = "",
    val catchupDays: Int = 0
)

class MainActivity : AppCompatActivity() {

    private val playlistUrl = "https://gvineon550-coder.github.io/8Z6evf3ezzM469/all_checked.m3u8"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val status = findViewById<TextView>(R.id.status)
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val channels = loadPlaylist()
                withContext(Dispatchers.Main) {
                    status.text = "Каналов: ${channels.size}"
                    list.adapter = ChannelAdapter(channels) { ch ->
                        val i = Intent(this@MainActivity, PlayerActivity::class.java)
                        i.putExtra("url", ch.url)
                        i.putExtra("name", ch.name)
                        i.putExtra("catchupSource", ch.catchupSource)
                        i.putExtra("catchupDays", ch.catchupDays)
                        startActivity(i)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    status.text = "Ошибка: ${e.message}"
                }
            }
        }
    }

    private fun loadPlaylist(): List<Channel> {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val req = Request.Builder().url(playlistUrl).build()
        val body = client.newCall(req).execute().body?.string() ?: ""
        return parseM3U(body)
    }

    private fun parseM3U(text: String): List<Channel> {
        val out = mutableListOf<Channel>()
        var name: String? = null
        var group = ""
        var catchupSource = ""
        var catchupDays = 0

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
            } else if (line.isNotEmpty() && !line.startsWith("#") && name != null) {
                out.add(Channel(name!!, line, group, catchupSource, catchupDays))
                name = null
                catchupSource = ""
                catchupDays = 0
            }
        }
        return out
    }
}

class ChannelAdapter(
    private val items: List<Channel>,
    private val onClick: (Channel) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.ch_name)
        val group: TextView = v.findViewById(R.id.ch_group)
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
