package com.nr4.app

import android.app.AlertDialog
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private var liveUrl: String = ""
    private var channelName: String = ""
    private var userAgent: String = ""
    private var catchupSource: String = ""
    private var catchupDays: Int = 0

    private lateinit var titleView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        liveUrl = intent.getStringExtra("url") ?: return
        channelName = intent.getStringExtra("name") ?: ""
        userAgent = intent.getStringExtra("userAgent") ?: ""
        catchupSource = intent.getStringExtra("catchupSource") ?: ""
        catchupDays = intent.getIntExtra("catchupDays", 0)

        titleView = findViewById(R.id.title)
        titleView.text = channelName

        val ua = userAgent.ifEmpty { "IPTV/1.0" }
        val dataSourceFactory = DefaultHttpDataSource.Factory().setUserAgent(ua)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
        findViewById<PlayerView>(R.id.player).player = player

        // Долгое нажатие на название канала — открыть архив
        titleView.setOnLongClickListener {
            showArchiveDialog()
            true
        }

        play(liveUrl)
    }

    private fun play(url: String) {
        player?.apply {
            stop()
            clearMediaItems()
            setMediaItem(MediaItem.fromUri(url))
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

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
