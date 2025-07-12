package com.spkdev.echomate

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.method.ScrollingMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.AdapterView.OnItemSelectedListener
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.squareup.picasso.Picasso
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader


class Settings : ComponentActivity() {
    val resultIntent = Intent()
    // Declare the ActivityResultLauncher for JSON file picker
    private lateinit var uploadJsonLauncher: ActivityResultLauncher<Intent>
    private var characterName = ""
    private val REQUEST_CODE_PREVIEW = 100
    private var chosenJSON: String = ""

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Read file content method
        fun readFileContent(uri: Uri): String {
            val contentResolver = contentResolver
            val inputStream = contentResolver.openInputStream(uri)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val stringBuilder = StringBuilder()
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                stringBuilder.append(line).append("\n")
            }

            reader.close()
            return stringBuilder.toString()
        }

        // Initialize the ActivityResultLauncher for the file picker
        uploadJsonLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    val selectedJson = result.data?.getStringExtra("selectedJson")
                    val characterName = result.data?.getStringExtra("characterName")
                    if (selectedJson != null) {
                        try {
                            chosenJSON = selectedJson

                            // Process the JSON content into a system prompt
                            val systemPrompt = processJsonInput(selectedJson)

                            // Apply the system prompt to AIBackend
                            Log.v("NewSystemPrompt", systemPrompt)
                            AIBackend.changeSetup(systemPrompt)
                            Toast.makeText(this, "System prompt applied!", Toast.LENGTH_SHORT).show()

                            // After processing, close the activity
                            closeActivity() // This will close the Settings activity
                        } catch (e: Exception) {
                            Toast.makeText(this, "Error processing JSON", Toast.LENGTH_SHORT).show()
                            e.printStackTrace()
                        }
                    }
                }
            }

        // Image Buttons Initialization
        val backButton = findViewById<ImageButton>(R.id.goBackButton)
        val resetSetup = findViewById<ImageButton>(R.id.resetSetupButton)
        val changeSetupButton = findViewById<ImageButton>(R.id.confirmChangeSetup)
        val modelChangeButton = findViewById<ImageButton>(R.id.confirmChangeModel)
        val resetModelButton = findViewById<ImageButton>(R.id.resetModelButton)
        val nameChangeButton = findViewById<ImageButton>(R.id.confirmChangeName)
        val spinnerButton = findViewById<ImageButton>(R.id.confirmSpinnerModel)
        val contextButtonConfirm = findViewById<ImageButton>(R.id.confirmChangeContext)
        val tempButton = findViewById<ImageButton>(R.id.confirmTemp)

        // EditText fields Initialization
        val newSetup = findViewById<EditText>(R.id.newSetup)
        val modelChangeText = findViewById<EditText>(R.id.newModel)
        val nameValue = findViewById<EditText>(R.id.nameSetup)
        val newContext = findViewById<EditText>(R.id.newContext)
        val newTemp = findViewById<EditText>(R.id.newTemp)

        // Spinner Initialization
        val spinnerChoice = findViewById<Spinner>(R.id.spinnerModels)
        val modelsArray = resources.getStringArray(R.array.models)

        // Button to upload JSON
        val uploadJsonButton = findViewById<Button>(R.id.uploadJsonButton)




        // Switch Initialization
        val contextSwitch = findViewById<Switch>(R.id.extendedHistorySwitch)

        // Switch Initialization
        val localModelSwitch = findViewById<Switch>(R.id.localModelSwitch)

        // back button
        backButton.setOnClickListener {

            closeActivity()
        }






        uploadJsonButton.setOnClickListener {
            val intent = Intent(this, JsonPreviewActivity::class.java)
            uploadJsonLauncher.launch(intent)
        }



        // Handle the name change and apply system prompt
        nameChangeButton.setOnClickListener {
            val jsonInput = nameValue.text.toString()

            // Check if the input is not blank
            if (jsonInput.isNotBlank()) {
                try {
                    // Parse the JSON input
                    val systemPrompt = processJsonInput(jsonInput)

                    // Use the generated system prompt
                    Toast.makeText(this, "System prompt created!", Toast.LENGTH_SHORT).show()
                    Log.d("SystemPrompt", systemPrompt) // Optional: Log for debugging

                    // Example: Store or use the system prompt
                    AIBackend.changeSetup(systemPrompt)

                } catch (e: JSONException) {
                    // Handle JSON parsing error
                    Toast.makeText(this, "Invalid JSON format!", Toast.LENGTH_SHORT).show()
                    e.printStackTrace()
                }
            } else {
                Toast.makeText(this, "Please paste valid JSON into the field!", Toast.LENGTH_SHORT).show()
            }

            // Clear the field after processing
            nameValue.text.clear()
        }

        // changing the setup
        changeSetupButton.setOnClickListener {
            val newSetupString = newSetup.text.toString().replace("\"", "'")
            AIBackend.changeSetup(newSetupString)
            Toast.makeText(this, "Changed setup", Toast.LENGTH_SHORT).show()
            newSetup.text.clear()
        }

        // resetting the setup
        resetSetup.setOnClickListener {
            AIBackend.resetSetup()
            Toast.makeText(this, "Setup has been reset", Toast.LENGTH_SHORT).show()
        }

        // Model choice from a dropdown list
        spinnerChoice.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modelsArray)
        spinnerChoice.onItemSelectedListener = object : OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                //todo
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // Not implemented
            }
        }

        spinnerButton.setOnClickListener {
            if (spinnerChoice.selectedItemPosition == 0) {
                Toast.makeText(this@Settings, "Please choose a model first", Toast.LENGTH_LONG).show()
            } else {
                val model = modelsArray[spinnerChoice.selectedItemPosition]
                AIBackend.changeModel(model)
                sharedInfo.saveDataString("Model", model)
                Toast.makeText(this@Settings, "Model $model has been set", Toast.LENGTH_LONG).show()
            }
        }

        // Changing the model manually
        modelChangeButton.setOnClickListener {
            val newModelString = modelChangeText.text.toString()
            AIBackend.changeModel(newModelString)
            sharedInfo.saveDataString("Model", newModelString)
            Toast.makeText(this, "Model has been changed to $newModelString", Toast.LENGTH_SHORT).show()
            modelChangeText.text.clear()
        }

        // Resetting the model
        resetModelButton.setOnClickListener {
            AIBackend.resetModel()
            Toast.makeText(this, "Model has been reset", Toast.LENGTH_SHORT).show()
        }

        // Experimental context switch added
        contextSwitch.setOnClickListener {
            if (contextSwitch.isChecked) {
                AIBackend.enableContext(true)
                Toast.makeText(this, "WARNING - this feature is experimental", Toast.LENGTH_LONG).show()
                Toast.makeText(this, "IT WILL NOT WORK PROPERLY ON MOST (IF ANY) MODELS", Toast.LENGTH_LONG).show()
            } else {
                AIBackend.enableContext(false)
            }
        }

        localModelSwitch.setOnClickListener{
            if (localModelSwitch.isChecked){
                AIBackend.enableLocalModel(true)
            } else {
                AIBackend.enableLocalModel(false)
            }
        }

        // Handling context switch state
        if (sharedInfo.exists("ContextSwitch")) {
            val value = sharedInfo.getDataString("ContextSwitch").toBoolean()
            AIBackend.enableContext(value)
            contextSwitch.isChecked = value
        } else {
            AIBackend.enableContext(false)
        }

        contextButtonConfirm.setOnClickListener {
            val newContextSize = newContext.text.toString()
            AIBackend.changeContextLength(newContextSize)
            Toast.makeText(this, "Changed context length to $newContextSize", Toast.LENGTH_LONG).show()
            newContext.text.clear()
        }

        tempButton.setOnClickListener {
            val newTemperature = newTemp.text.toString()
            AIBackend.newTemperature(newTemperature)
            Toast.makeText(this, "Changed temp to $newTemperature", Toast.LENGTH_LONG).show()
            newTemp.text.clear()
        }
    }

    private fun closeActivity() {
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    // Public function to add an alternate greeting by index
    companion object {
        // Array to hold alternate greetings globally
        private val alternateGreetingsArray = mutableListOf<String>()
        private var characterName = ""

        fun countAlternateGreetings(): Int{
            return alternateGreetingsArray.size
        }

        // Function to add an alternate greeting
        fun addAlternateGreeting(index: Int): String {
            // Check if the index is valid and below 9
            try {
                Log.v("alternateGreetings", alternateGreetingsArray.toString())
                if (index in alternateGreetingsArray.indices) {
                    val alternateGreeting = alternateGreetingsArray[index]
                        .replace("{{user}}", "Stan")
                        .replace("{{char}}", characterName)
                        .replace("\"", "'") // Escape quotes
                    //AIBackend.removeEntry()
                    //AIBackend.addEntry("user", "Let's begin.")

                    AIBackend.addEntry("assistant", alternateGreeting)
                    Log.d("AlternateGreeting", "Added alternate greeting: $alternateGreeting")
                    return alternateGreeting
                } else {
                    Log.w("AlternateGreeting", "Index $index is valid but not present in alternateGreetingsArray.")
                }
            } catch (e: Exception) {
                Log.e("AlternateGreeting", "Error processing alternate greeting at index $index", e)
            }
            return ""
        }

        // Function to load alternate greetings into the array
        fun loadAlternateGreetings(alternateGreetings: JSONArray, name: String, firstMessage: String?) {
            alternateGreetingsArray.clear() // Clear previous entries

            characterName = name

            // If no alternate greetings, just add firstMessage
            if (alternateGreetings.length() == 0) {
                if (!firstMessage.isNullOrEmpty()) {
                    alternateGreetingsArray.add(firstMessage) // Add firstMessage as the only greeting
                }
            } else {
                // Add firstMessage as the greeting at index 0
                if (!firstMessage.isNullOrEmpty()) {
                    alternateGreetingsArray.add(firstMessage) // Insert firstMessage at index 0
                }

                // Add the alternate greetings starting from index 1
                for (i in 0 until alternateGreetings.length()) {
                    try {
                        val item = alternateGreetings.getString(i).trim()
                        Log.d("AlternateGreeting", "Processing raw item[$i]: $item")

                        if (item.isNotEmpty()) {
                            alternateGreetingsArray.add(item) // Add the alternate greeting directly
                        }

                        Log.d("AlternateGreeting", "Loaded item[$i]: $item")

                    } catch (e: Exception) {
                        Log.e("AlternateGreeting", "Error processing alternate greeting at index $i", e)
                    }
                }
            }

            // Log the final greetings array to check if it's correctly populated
            Log.d("AlternateGreeting", "Final loaded greetings: $alternateGreetingsArray")
        }
    }

    private fun processJsonInput(jsonString: String): String {
        val json = JSONObject(jsonString)
        val data = json.getJSONObject("data")

        // Extract fields
        val name = data.getString("name")
        var description = data.getString("description")
        val firstMessage = data.optString("first_mes", "No first message provided.")
        val scenario = data.optString("scenario", "No scenario available.")
        val pnglink = data.optString("avatar", "false")
        val personality = data.optString ("personality", "Nothing added, use the description")



        resultIntent.putExtra("updatedImageUrl", pnglink)
        resultIntent.putExtra("name", name)
        resultIntent.putExtra("firstMessage", firstMessage)

        // Replace placeholders
        description = description.replace("{{char}}", name).replace("{{user}}", "Stan")

        if (firstMessage.isNotEmpty()) {
            Log.v("FirstMessage", "Added first message")
            AIBackend.addEntry("assistant", firstMessage.replace("\"", "'"))
        }

        // Process alternate greetings if present
        if (data.has("alternate_greetings")) {
            val alternateGreetings = data.getJSONArray("alternate_greetings")
            loadAlternateGreetings(alternateGreetings, name, firstMessage) // Load into global array with firstMessage at index 0
        } else {
            // If no alternate greetings, load just the firstMessage
            loadAlternateGreetings(JSONArray(), name, firstMessage) // Pass an empty array if no alternate greetings
        }

        return ("You are a to act as $name. Always stay in character and keep your messages not too long but also not too short unless stated otherwise. If user sends a message with [] brackets, its an instruction for you not the character " +
                "Here is the character you need to play: " +
                ", You are $name, $description " +
                ", Scenario: $scenario"+
                ", Personality: $personality" +
                ", you are to keep actions and thoughts in asteriks.")
            .replace("\"", "\\\"").replace("\\r", "")
    }




}
