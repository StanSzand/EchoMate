package com.spkdev.echomate

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.InputStreamReader

class DetailsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_details)

        val name = intent.getStringExtra("name")
        val description = intent.getStringExtra("description")
        val jsonUri = intent.getStringExtra("jsonUri")

        val textViewName: TextView = findViewById(R.id.textViewName)
        val textViewDescription: TextView = findViewById(R.id.textViewDescription)
        val buttonSelect: Button = findViewById(R.id.buttonSelect)

        textViewName.text = name
        textViewDescription.text = description

        buttonSelect.setOnClickListener {
            val resultIntent = intent
            if (!jsonUri.isNullOrBlank()) {
                resultIntent.putExtra("selectedJson", readTextFromUri(Uri.parse(jsonUri)))
                resultIntent.putExtra("selectedJsonUri", jsonUri)
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }
    }

    private fun readTextFromUri(uri: Uri): String {
        val inputStream = contentResolver.openInputStream(uri) ?: return ""
        return BufferedReader(InputStreamReader(inputStream)).use { it.readText() }
    }
}
