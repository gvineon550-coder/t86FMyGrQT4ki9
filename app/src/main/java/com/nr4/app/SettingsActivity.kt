package com.nr4.app

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var listWrap: LinearLayout
    private lateinit var activeUrl: TextView

    private val openFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        val path = AppPrefs.importFile(this, uri, "playlist.m3u")
        if (path == null) {
            Toast.makeText(this, "Не удалось импортировать", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        val name = File(path).name
        val list = AppPrefs.getPlaylists(this)
        list.add(SourceItem(name, path))
        AppPrefs.setPlaylists(this, list)
        if (AppPrefs.getActive(this).isEmpty()) AppPrefs.setActive(this, path)
        render()
        Toast.makeText(this, "Добавлено: $name", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        listWrap = findViewById(R.id.list_playlists)
        activeUrl = findViewById(R.id.active_url)

        findViewById<Button>(R.id.btn_add_url).setOnClickListener { addUrl() }
        findViewById<Button>(R.id.btn_add_file).setOnClickListener {
            openFile.launch(arrayOf("*/*"))
        }

        findViewById<TextView>(R.id.item_epg).setOnClickListener { soon("EPG") }
        findViewById<TextView>(R.id.item_decoder).setOnClickListener { soon("Декодер") }
        findViewById<TextView>(R.id.item_sleep).setOnClickListener { soon("Таймер сна") }
        findViewById<TextView>(R.id.item_parental).setOnClickListener { soon("Родительский контроль") }

        render()
    }

    private fun addUrl() {
        val input = EditText(this).apply {
            hint = "https://example.com/playlist.m3u8"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setPadding(40, 40, 40, 40)
        }
        AlertDialog.Builder(this)
            .setTitle("Добавить плейлист по URL")
            .setView(input)
            .setPositiveButton("Добавить") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isEmpty()) return@setPositiveButton
                val name = url.substringAfterLast('/').ifEmpty { "playlist" }
                val list = AppPrefs.getPlaylists(this)
                list.add(SourceItem(name, url))
                AppPrefs.setPlaylists(this, list)
                if (AppPrefs.getActive(this).isEmpty()) AppPrefs.setActive(this, url)
                render()
                Toast.makeText(this, "Добавлено: $name", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun render() {
        val list = AppPrefs.getPlaylists(this)
        val active = AppPrefs.getActive(this)

        activeUrl.text = "Активный: " + active.ifEmpty { "встроенный (all_checked.m3u8)" }

        listWrap.removeAllViews()

        val builtInRow = addRow(
            name = "Встроенный (all_checked.m3u8)",
            url = "",
            isActive = active.isEmpty(),
            deletable = false
        )
        builtInRow.findViewById<TextView>(R.id.row_name).setOnClickListener {
            AppPrefs.setActive(this, "")
            render()
            Toast.makeText(this, "Активный: встроенный", Toast.LENGTH_SHORT).show()
        }

        for ((idx, item) in list.withIndex()) {
            val row = addRow(
                name = item.name,
                url = item.url,
                isActive = active == item.url,
                deletable = true
            )
            row.findViewById<TextView>(R.id.row_delete).setOnClickListener {
                val cur = AppPrefs.getPlaylists(this)
                if (idx in cur.indices) cur.removeAt(idx)
                AppPrefs.setPlaylists(this, cur)
                if (AppPrefs.getActive(this) == item.url) {
                    AppPrefs.setActive(this, "")
                }
                render()
            }
            row.findViewById<TextView>(R.id.row_name).setOnClickListener {
                AppPrefs.setActive(this, item.url)
                render()
                Toast.makeText(this, "Активный: ${item.name}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun addRow(name: String, url: String, isActive: Boolean, deletable: Boolean): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 24, 24, 24)
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(if (isActive) 0xFF1a3a5f.toInt() else 0xFF17191d.toInt())
        }

        val nameView = TextView(this).apply {
            id = R.id.row_name
            text = if (url.isEmpty()) name else "$name\n$url"
            textSize = 14f
            setTextColor(0xFFe6e6e6.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(nameView)

        if (isActive) {
            val badge = TextView(this).apply {
                text = "✓"
                textSize = 18f
                setTextColor(0xFF4ade80.toInt())
                setPadding(16, 0, 16, 0)
            }
            row.addView(badge)
        }

        if (deletable) {
            val del = TextView(this).apply {
                id = R.id.row_delete
                text = "✕"
                textSize = 18f
                setTextColor(0xFFf87171.toInt())
                setPadding(20, 0, 4, 0)
            }
            row.addView(del)
        }

        listWrap.addView(row)
        return row
    }

    private fun soon(name: String) {
        Toast.makeText(this, "$name — в следующем обновлении", Toast.LENGTH_SHORT).show()
    }
}
