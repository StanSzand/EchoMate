package com.spkdev.echomate

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : ComponentActivity() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var chatAdapter: ChatAdapter
    private var messages = mutableListOf<ChatMessage>()


    @SuppressLint("NotifyDataSetChanged")
    override fun onCreate(savedInstanceState: Bundle?) {




        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val requestTextField = findViewById<EditText>(R.id.textInput)
        val sendRequestButton = findViewById<ImageButton>(R.id.sendRequest)
        val settingsButton = findViewById<ImageButton>(R.id.openOptions)
        val nameInput = EditText(this)


        sharedInfo.initialize(applicationContext)
        //sharedInfo.clearPreferences()

        if (sharedInfo.exists("Name")) {
            AIBackend.changeName(sharedInfo.getDataString("Name"))
        }else{
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
        }else{
            AIBackend.resetModel()
        }

        if (sharedInfo.exists("Setup")){
            AIBackend.changeSetup(sharedInfo.getDataString("Setup"))
        }else{
            AIBackend.resetSetup()
        }

        if (sharedInfo.exists("ContextSwitch")){
            val value = sharedInfo.getDataString("ContextSwitch").toBoolean()
            AIBackend.enableContext(value)

        }else{
            AIBackend.enableContext(false)
        }


        // Set up RecyclerView
        recyclerView = findViewById(R.id.recycler_view)
        chatAdapter = ChatAdapter(messages)
        recyclerView.adapter = chatAdapter
        recyclerView.layoutManager = LinearLayoutManager(this)

        messages.add(ChatMessage("Hello! My name is Echo, how can I help you?", false))
        chatAdapter.notifyDataSetChanged()




        sendRequestButton.setOnClickListener{
            val messageText = requestTextField.text.toString()
            Toast.makeText(this, "Sending", Toast.LENGTH_SHORT).show()


            addMessage(ChatMessage(messageText, true))
            requestTextField.text.clear() // Clear input after sending

            // Example usage of AIBackend.getResponse
            AIBackend.getResponse(messageText) { response ->
                runOnUiThread {
                    if (response == "error"){
                        Toast.makeText(this, "The request went 404 mode - please try again or reset the settings", Toast.LENGTH_LONG).show()
                        addMessage(ChatMessage("ERROR, for no apparent fucking reason - please try again or reset the setup in settings ", false))
                    }else{
                        addMessage(ChatMessage(response, false))
                    }
                }

            }

        }


        settingsButton.setOnClickListener {
            startActivity(Intent(this@MainActivity,Settings::class.java))

        }

    }

    private fun addMessage(message: ChatMessage) {
        messages.add(message)
        chatAdapter.notifyItemInserted(messages.size - 1)
        recyclerView.scrollToPosition(messages.size - 1)

    }


}

