package com.nr4.app

import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<TextView>(R.id.item_playlists).setOnClickListener {
            soon("Плейлисты")
        }
        findViewById<TextView>(R.id.item_epg).setOnClickListener {
            soon("Программа передач")
        }
        findViewById<TextView>(R.id.item_decoder).setOnClickListener {
            soon("Декодер")
        }
        findViewById<TextView>(R.id.item_sleep).setOnClickListener {
            soon("Таймер сна")
        }
        findViewById<TextView>(R.id.item_parental).setOnClickListener {
            soon("Родительский контроль")
        }
        findViewById<TextView>(R.id.item_theme).setOnClickListener {
            soon("Тема")
        }
        findViewById<TextView>(R.id.item_about).setOnClickListener {
            val tv = TextView(this).apply {
                text = "IPTV\nВерсия 1.0\n\nПлеер на ExoPlayer\nПлейлист: все_checked"
                textSize = 15f
                setTextColor(0xFFe6e6e6.toInt())
                setPadding(40, 40, 40, 40)
                gravity = Gravity.CENTER
            }
            android.app.AlertDialog.Builder(this)
                .setView(tv)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun soon(name: String) {
        Toast.makeText(this, "$name — скоро", Toast.LENGTH_SHORT).show()
    }
}
