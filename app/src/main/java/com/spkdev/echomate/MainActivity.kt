package com.spkdev.echomate

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.squareup.picasso.Picasso
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException


class  MainActivity : ComponentActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var chatAdapter: ChatAdapter
    private var messages = mutableListOf<ChatMessage>()

    private lateinit var imageView: ImageView  // Declare ImageView for the avatar
    private lateinit var appName: TextView


    @SuppressLint("NotifyDataSetChanged")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val requestTextField = findViewById<EditText>(R.id.textInput)
        val sendRequestButton = findViewById<ImageButton>(R.id.sendRequest)
        val settingsButton = findViewById<ImageButton>(R.id.openOptions)
        val wipeButton = findViewById<ImageButton>(R.id.resetScreen)


        // Initialize the ImageView where the avatar will be loaded
        imageView = findViewById(R.id.topLeftIcon)
        appName = findViewById(R.id.appName)

        sharedInfo.initialize(applicationContext)

        // Check and load user name, model, setup, and context switch
        initializeUserSettings()

        // Set up RecyclerView
        recyclerView = findViewById(R.id.recycler_view)
        chatAdapter = ChatAdapter(messages)
        recyclerView.adapter = chatAdapter
        recyclerView.layoutManager = LinearLayoutManager(this)

        // Initial message
        messages.add(ChatMessage("Hello! My name is Echo, how can I help you?", false))
        chatAdapter.notifyDataSetChanged()

        // Button for clearing messages
        wipeButton.setOnClickListener {
            clearMessages()
            Toast.makeText(this, "Cleared messages", Toast.LENGTH_LONG).show()
        }


        appName.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Details:")
                .setMessage("Character name: " + appName.text)
                .setPositiveButton("SAVE") { _, _ ->
                    // Ask for confirmation before saving
                    AlertDialog.Builder(this)
                        .setTitle("Confirmation")
                        .setMessage("Are you sure you want to save the chat history?")
                        .setPositiveButton("Yes") { _, _ ->
                            saveChatHistory() // Save functionality
                        }
                        .setNegativeButton("No", null) // Dismiss dialog
                        .show()
                }
                .setNegativeButton("LOAD") { _, _ ->
                    // Ask for confirmation before loading
                    AlertDialog.Builder(this)
                        .setTitle("Confirmation")
                        .setMessage("Are you sure you want to load the chat history?")
                        .setPositiveButton("Yes") { _, _ ->
                            loadChatHistory() // Load functionality
                        }
                        .setNegativeButton("No", null) // Dismiss dialog
                        .show()
                }
                .setNeutralButton("OK", null) // Dismiss dialog
                .show()
        }


        // Buttons for loading and saving history
//        val saveButton = findViewById<ImageButton>(R.id.saveButton)
//        val loadButton = findViewById<ImageButton>(R.id.loadButton)
//
//        saveButton.setOnClickListener {
//            saveChatHistory() // Save the chat history when clicked
//        }
//
//        loadButton.setOnClickListener {
//            loadChatHistory() // Load the chat history when clicked
//        }

        // Send request button logic
        sendRequestButton.setOnClickListener {
            val messageText = requestTextField.text.toString()
            try{
                if(messageText.toInt() in 0.. Settings.countAlternateGreetings() ) {
                    val message = Settings.addAlternateGreeting(messageText.toInt() - 1)
                    addMessage(ChatMessage(message, false))
                    requestTextField.text.clear()
                    Log.v("RAN", "YES")
                }else{
                    Log.v("RAN", "NO")
                }
            }
            catch(e: Exception){
                if (messageText != null && messageText.startsWith("setP")) {
                    AIBackend.setTopP(messageText.replace("setP ", ""))
                    requestTextField.text.clear()
                    Toast.makeText(
                        this,
                        "topP set to ${messageText.replace("setP ", "")}",
                        Toast.LENGTH_SHORT).show()
                }
                else if (messageText == ""){
                    Toast.makeText(
                        this,
                        "Letting AI continue",
                        Toast.LENGTH_SHORT).show()
                    AIBackend.getResponse("*Continue*"){ response ->
                        runOnUiThread {
                            if (response == "error") {
                                Toast.makeText(
                                    this,
                                    "The request did not go through.",
                                    Toast.LENGTH_LONG
                                ).show()
                                addMessage(
                                    ChatMessage(
                                        "ERROR, for no apparent reason - please try again or reset the setup in settings ",
                                        false
                                    )
                                )
                                AIBackend.removeEntry()
                            } else {
                                addMessage(ChatMessage(response, false))
                            }
                        }
                    }
                }else{
                    Toast.makeText(this, "Sending", Toast.LENGTH_SHORT).show()
                    addMessage(ChatMessage(messageText, true))
                    requestTextField.text.clear() // Clear input after sending

                    // Sends a post request and checks for an error, if no error is detected it shows the response using messageAdapter
                    AIBackend.getResponse(messageText) { response ->
                        runOnUiThread {
                            if (response == "error") {
                                Toast.makeText(
                                    this,
                                    "The request went 404 mode - please try again or reset the settings",
                                    Toast.LENGTH_LONG
                                ).show()
                                addMessage(
                                    ChatMessage(
                                        "ERROR, for no apparent fucking reason - please try again or reset the setup in settings ",
                                        false
                                    )
                                )
                                AIBackend.removeEntry()
                            } else {
                                addMessage(ChatMessage(response, false))
                            }
                        }
                    }
                }

            }
        }

        // Settings button to open Settings activity
        settingsButton.setOnClickListener {
            val intent = Intent(this@MainActivity, Settings::class.java)
            startActivityForResult(intent, REQUEST_CODE_IMAGE)  // Start Settings activity and wait for result
        }
    }

    private fun saveChatHistory() {
        val characterName = appName.text
        val fileName = "chat_history_$characterName"
        val chatHistoryFile = File(getExternalFilesDir(null), fileName)
        Log.d("SaveChatHistory", "Saving to file: ${chatHistoryFile.absolutePath}")

        try {
            FileWriter(chatHistoryFile).use { writer ->
                val jsonArray = JSONArray()
                for (message in messages) {
                    val jsonMessage = JSONObject()
                    jsonMessage.put("message", message.message)
                    jsonMessage.put("isSentByCurrentUser", message.isSentByCurrentUser)
                    jsonArray.put(jsonMessage)
                }
                writer.write(jsonArray.toString())
            }
            Toast.makeText(this, "Chat history saved!", Toast.LENGTH_SHORT).show()
        } catch (e: IOException) {
            Log.e("SaveChatHistory", "Error saving chat history: ${e.message}")
            Toast.makeText(this, "Error saving chat history.", Toast.LENGTH_SHORT).show()
        }
    }


    private fun loadChatHistory() {
        val characterName = appName.text
        val fileName = "chat_history_$characterName"
        val chatHistoryFile = File(getExternalFilesDir(null), fileName)
        Log.d("Pressdownload", "true")

        if (chatHistoryFile.exists()) {
            try {
                val jsonArray = JSONArray(FileReader(chatHistoryFile).readText()) // Read and parse the JSON array

                // Clear existing messages and add loaded messages
                messages.clear()
                for (i in 0 until jsonArray.length()) {
                    val jsonMessage = jsonArray.getJSONObject(i)
                    val message = ChatMessage(
                        jsonMessage.getString("message"),
                        jsonMessage.getBoolean("isSentByCurrentUser")
                    )
                    messages.add(message)
                    if(jsonMessage.getBoolean("isSentByCurrentUser")){
                        AIBackend.addEntry("user", jsonMessage.getString("message"))
                    }else{
                        AIBackend.addEntry("assistant", jsonMessage.getString("message"))
                    }

                }

                chatAdapter.notifyDataSetChanged()
                Toast.makeText(this, "Chat history loaded!", Toast.LENGTH_SHORT).show()
            } catch (e: IOException) {
                e.printStackTrace()
                Toast.makeText(this, "Error loading chat history.", Toast.LENGTH_SHORT).show()
            } catch (e: JSONException) {
                e.printStackTrace()
                Toast.makeText(this, "Invalid chat history format.", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "No saved chat history found.", Toast.LENGTH_SHORT).show()
        }
    }

    // Handle the result when Settings activity finishes
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE_IMAGE && resultCode == RESULT_OK) {
            // Retrieve the updated image URL from the result


            val updatedImageUrl = data?.getStringExtra("updatedImageUrl")
            val newName = data?.getStringExtra("name")
            val firstMessage = data?.getStringExtra("firstMessage")

            Log.v("firstMessage", firstMessage.toString())
            if (!newName.isNullOrEmpty()) {
                appName.text = newName
                clearMessages()
            }

            if (firstMessage != null) {
                //AIBackend.addEntry("user", "Let's begin.")
                val userName = sharedInfo.getDataString("Name").ifBlank { "User" }
                val introMessage = firstMessage.replace("{{user}}", userName).replace("{{char}}", appName.text.toString())
                AIBackend.addEntry("assistant", introMessage)
                addMessage(ChatMessage(introMessage, false))
            }

            Log.v("updatedUrl", updatedImageUrl.toString())



            // Load the updated image URL into the ImageView using Picasso
            if (!updatedImageUrl.isNullOrEmpty()) {
                imageView.setImageTintList(null)
                Picasso.get()
                    .load(updatedImageUrl)
                    .resize(0, 250).centerInside()
                    .into(imageView, object : com.squareup.picasso.Callback {
                        override fun onSuccess() {
                            // Image loaded successfully
                            Log.d("ImageLoad", "Image loaded successfully")
                        }

                        override fun onError(e: Exception?) {
                            // Log any errors
                            Log.e("ImageLoadError", "Error loading image: ${e?.message}")
                        }
                    })
            }
        }
    }

    // Add a message to the chat
    fun addMessage(message: ChatMessage) {
        messages.add(message)
        chatAdapter.notifyItemInserted(messages.size - 1)
        recyclerView.scrollToPosition(messages.size - 1)
    }

    // Clear the chat messages
    @SuppressLint("NotifyDataSetChanged")
    private fun clearMessages() {
        messages.clear()
        chatAdapter.notifyDataSetChanged()
        AIBackend.wipeChat()
    }

    // Initialize user settings like name, model, and setup
    private fun initializeUserSettings() {
        val nameInput = EditText(this)
        if (sharedInfo.exists("Name")) {
            AIBackend.changeName(sharedInfo.getDataString("Name"))
        } else {
            val nameDialog = AlertDialog.Builder(this)
                .setMessage("Please enter your name below:")
                .setView(nameInput)
                .setPositiveButton("Enter") { dialog, which ->
                    val enteredText = nameInput.text.toString()
                    sharedInfo.saveDataString("Name", enteredText)
                    AIBackend.changeName(enteredText)
                }
                .create()
            nameDialog.show()
        }

        if (sharedInfo.exists("Model")) {
            AIBackend.changeModel(sharedInfo.getDataString("Model"))
        } else {
            AIBackend.resetModel()
        }

        if (sharedInfo.exists("Setup")) {
            AIBackend.changeSetup(sharedInfo.getDataString("Setup"))
        } else {
            AIBackend.resetSetup()
        }

        if (sharedInfo.exists("ContextSwitch")) {
            val value = sharedInfo.getDataString("ContextSwitch").toBoolean()
            AIBackend.enableContext(value)
        } else {
            AIBackend.enableContext(false)
        }
    }

    // Constant for the request code to identify the result from Settings activity
    private val REQUEST_CODE_IMAGE = 100
}
