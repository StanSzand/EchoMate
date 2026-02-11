package com.spkdev.echomate

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class DetailsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_details)

        val name = intent.getStringExtra("name")
        val description = intent.getStringExtra("description")
        val jsonContent = intent.getStringExtra("jsonObject")

        val textViewName: TextView = findViewById(R.id.textViewName)
        val textViewDescription: TextView = findViewById(R.id.textViewDescription)
        val buttonSelect: Button = findViewById(R.id.buttonSelect)

        textViewName.text = name
        textViewDescription.text = description // content of the whole json file

        buttonSelect.setOnClickListener {
            // Set the result to pass back to Settings
            val resultIntent = intent
            resultIntent.putExtra("selectedJson", jsonContent)
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }
    }


}
