package com.nr4.app

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private var liveUrl: String = ""
    private var channelName: String = ""
    private var catchupSource: String = ""
    private var catchupDays: Int = 0

    private lateinit var titleView: TextView
    private lateinit var archiveBtn: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        liveUrl = intent.getStringExtra("url") ?: return
        channelName = intent.getStringExtra("name") ?: ""
        catchupSource = intent.getStringExtra("catchupSource") ?: ""
        catchupDays = intent.getIntExtra("catchupDays", 0)

        titleView = findViewById(R.id.title)
        titleView.text = channelName

        player = ExoPlayer.Builder(this).build()
        findViewById<PlayerView>(R.id.player).player = player

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

    // Разбор catchup-source и подстановка offset
    // Пример: catchup-source="?offset=-${offset}"
    // Для архива на 1 час назад: подставляем ${offset} = 3600
    private fun buildArchiveUrl(secondsAgo: Int): String? {
        if (catchupSource.isEmpty()) return null

        // Заменяем ${offset} на количество секунд
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

        val maxHours = catchupDays * 24
        val options = mutableListOf<String>()

        // предлагаем шаг раз в час, но не больше 12 пунктов
        val step = if (maxHours > 12) maxHours / 12 else 1
        var h = step
        while (h <= maxHours && options.size < 24) {
            options.add("$h ч назад")
            h += step
        }

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
            .setItems(options.toTypedArray()) { _, which ->
                val hours = when (which) {
                    0 -> step
                    else -> {
                        var acc = step
                        var i = 0
                        while (i < which) { acc += step; i++ }
                        acc
                    }
                }
                val secondsAgo = hours * 3600
                val archiveUrl = buildArchiveUrl(secondsAgo)
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
