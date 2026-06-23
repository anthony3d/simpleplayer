package com.example.androidautobuildapk

import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.widget.Button
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tableTags: TableLayout

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            showTags(it.toString())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)  // ← Здесь R теперь будет найден

        tableTags = findViewById(R.id.tableTags)  // ← И здесь
        val btnLoad = findViewById<Button>(R.id.btnLoad)  // ← И здесь

        btnLoad.setOnClickListener {
            pickFile.launch("audio/*")
        }
    }

    private fun showTags(filePath: String) {
        tableTags.removeAllViews()

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, android.net.Uri.parse(filePath))

            val tags = mapOf(
                "Название" to retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                "Исполнитель" to retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                "Альбом" to retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                "Год" to retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR),
                "Жанр" to retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE),
                "Композитор" to retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER),
                "Длительность" to formatDuration(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull())
            )

            for ((key, value) in tags) {
                addRow(key, value ?: "—")
            }

        } catch (e: Exception) {
            addRow("Ошибка", e.message ?: "Не удалось прочитать теги")
        } finally {
            retriever.release()
        }
    }

    private fun addRow(label: String, value: String) {
        val row = TableRow(this).apply {
            layoutParams = TableRow.LayoutParams(
                TableRow.LayoutParams.MATCH_PARENT,
                TableRow.LayoutParams.WRAP_CONTENT
            )
        }

        val tvLabel = TextView(this).apply {
            text = label
            setPadding(8, 8, 16, 8)
            textSize = 16f
        }

        val tvValue = TextView(this).apply {
            text = value
            setPadding(8, 8, 16, 8)
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        row.addView(tvLabel)
        row.addView(tvValue)
        tableTags.addView(row)

        // Разделитель
        val divider = android.view.View(this).apply {
            layoutParams = TableRow.LayoutParams(
                TableRow.LayoutParams.MATCH_PARENT,
                1
            )
            setBackgroundColor(android.graphics.Color.LTGRAY)
        }
        tableTags.addView(divider)
    }

    private fun formatDuration(millis: Long?): String {
        if (millis == null || millis <= 0) return "—"
        val seconds = millis / 1000
        val mins = seconds / 60
        val secs = seconds % 60
        return String.format("%d:%02d", mins, secs)
    }
}
