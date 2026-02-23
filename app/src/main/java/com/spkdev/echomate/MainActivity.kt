package com.spkdev.echomate

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import android.Manifest
import android.app.PendingIntent
import android.os.Build
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresPermission
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.squareup.picasso.Picasso
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException

class MainActivity : ComponentActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var chatAdapter: ChatAdapter
    private var messages = mutableListOf<ChatMessage>()

    private lateinit var imageView: ImageView
    private lateinit var appName: TextView

    // --- Typing pipeline (single watcher + debounce, prevents stacked listeners) ---
    private var inputWatcher: android.text.TextWatcher? = null
    private var inputJob: Job? = null
    private val textChanges = MutableSharedFlow<String>(extraBufferCapacity = 1)

    // Remember what we last tried (for "save")
    private var lastTriedJson: String? = null

    private val pendingImageForMessageUris = mutableListOf<Uri>()


    // Track the currently applied character + prompt (so we can snapshot/restore)
    private var currentCharacterJson: String? = null
    private var currentAvatarUrl: String? = null
    private var currentSystemPrompt: String? = null

    // One-step snapshot you can revert to with "back"
    private data class SessionSnapshot(
        val name: String,
        val avatarUrl: String?,
        val systemPrompt: String?,
        val messages: List<ChatMessage>,
        val characterJson: String?
    )
    private var preTrySnapshot: SessionSnapshot? = null





    @SuppressLint("NotifyDataSetChanged")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val requestTextField = findViewById<EditText>(R.id.textInput)
        val sendRequestButton = findViewById<ImageButton>(R.id.sendRequest)
        val attachImageButton = findViewById<ImageButton>(R.id.attachImage)
        val settingsButton = findViewById<ImageButton>(R.id.openOptions)
        val wipeButton = findViewById<ImageButton>(R.id.resetScreen)
        val rerollButton = findViewById<ImageButton>(R.id.rerollButton)

        val dialogView = layoutInflater.inflate(R.layout.custom_dialog, null)

        imageView = findViewById(R.id.topLeftIcon)
        appName = findViewById(R.id.appName)

        sharedInfo.initialize(applicationContext)
        initializeUserSettings()

        recyclerView = findViewById(R.id.recycler_view)
        // create adapter with callback
        chatAdapter = ChatAdapter(messages) { pos, newText ->
            val old = messages[pos]
            messages[pos] = old.copy(message = newText)
            chatAdapter.notifyItemChanged(pos)

            // keep backend history in sync
            AIBackend.editHistoryMessageAt(pos, newText)
            AIBackend.rebuildSystemFromHistory(this) // optional, refresh lore/system
        }

        recyclerView.adapter = chatAdapter


        recyclerView.apply {
            adapter = chatAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
            // Perf tweaks to reduce IME jank during typing:
            itemAnimator = null                   // animations near keyboard cause stutter
            setHasFixedSize(true)                 // row size is stable
            setItemViewCacheSize(20)              // fewer binds on fast scroll
        }

        createNotificationChannel()

        // If your ChatAdapter supports stable IDs, uncomment the next line and implement getItemId()
        // chatAdapter.setHasStableIds(true)

        messages.add(ChatMessage("Hello! My name is Echo, how can I help you?", false))
        chatAdapter.notifyItemInserted(messages.size - 1)
        recyclerView.scrollToPosition(messages.size - 1)

        // --- Attach a single watcher & debounce typing (prevents stacked listeners after selections) ---
        attachWatcherOnce(requestTextField)
        startTypingPipeline {
            // If you add per-keystroke logic later (e.g., live suggestions), do it OFF main here.
            // Right now we intentionally do nothing heavy while typing to keep IME smooth.
        }

        wipeButton.setOnClickListener {
            clearMessages()
            Toast.makeText(this, "Cleared messages", Toast.LENGTH_LONG).show()
        }

        appName.setOnClickListener {
            val dialogView = layoutInflater.inflate(R.layout.custom_dialog, null, false)

            // fill dynamic content
            dialogView.findViewById<TextView>(R.id.tvLore).text =
                "Lorebook:\n" + AIBackend.getLorebookName()

            dialogView.findViewById<TextView>(R.id.charName).text =
                "Character Name:\n" + appName.text

            val dialog = AlertDialog.Builder(this)
                .setView(dialogView)
                .create()

            dialogView.findViewById<Button>(R.id.btnSave).setOnClickListener {
                saveChatHistory()
                dialog.dismiss()
            }
            dialogView.findViewById<Button>(R.id.btnLoad).setOnClickListener {
                loadChatHistory()
                dialog.dismiss()
            }
            dialogView.findViewById<Button>(R.id.btnDelete).setOnClickListener {
                deleteSavedChat()
                dialog.dismiss()
            }
            dialogView.findViewById<Button>(R.id.btnResetLore).setOnClickListener {
                AIBackend.resetLorebook()
                Toast.makeText(this, "Lorebook has been reset", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }

            dialog.show()
        }



        sendRequestButton.setOnClickListener {
            val messageText = requestTextField.text.toString()
            try {
                if (messageText.toInt() in 1..Settings.countAlternateGreetings()) {
                    val message = Settings.addAlternateGreeting(messageText.toInt() - 1)
                    AIBackend.wipeChat(this)   // clear backend context
                    clearMessages()            // clears UI + backend again (safe double-clear)
                    addMessage(ChatMessage(message, false))
                    requestTextField.text.clear()
                } else {
                    Log.v("RAN", "NO")
                }
            } catch (e: Exception) {
                if (messageText.startsWith("setP")) {
                    AIBackend.setTopP(messageText.replace("setP ", ""))
                    requestTextField.text.clear()
                    Toast.makeText(this, "topP set", Toast.LENGTH_SHORT).show()
                } else if (messageText == "") {
                    val imageUris = pendingImageForMessageUris.toList()
                    if (imageUris.isNotEmpty()) {
                        val imageDataUrls = buildImageDataUrls(imageUris)
                        if (imageDataUrls.isNotEmpty()) {
                            Toast.makeText(this, "Sending ${imageDataUrls.size} image(s)", Toast.LENGTH_SHORT).show()
                            addMessage(ChatMessage("", true, imageUris.map { it.toString() }))
                            requestTextField.text.clear()
                            pendingImageForMessageUris.clear()

                            AIBackend.getResponse(this, "", imageDataUrls) { response ->
                                runOnUiThread {
                                    if (response == "error") {
                                        Toast.makeText(this, "The request failed", Toast.LENGTH_LONG).show()
                                        addMessage(ChatMessage("ERROR - please try again", false))
                                        AIBackend.removeEntry()
                                    } else {
                                        if (isForeground) {
                                            addMessage(ChatMessage(response, false))
                                        } else {
                                            savePendingAssistantReply(response)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Toast.makeText(this, "Letting AI continue", Toast.LENGTH_SHORT).show()
                        AIBackend.getResponse(this, "*continue*") { response ->
                            runOnUiThread {
                                if (response == "error") {
                                    Toast.makeText(this, "The request failed", Toast.LENGTH_LONG).show()
                                    addMessage(ChatMessage("ERROR - please try again", false))
                                    AIBackend.removeEntry()
                                } else {
                                    if (isForeground) {
                                        addMessage(ChatMessage(response, false))
                                    } else {
                                        // App is backgrounded: buffer it to disk and show on return
                                        savePendingAssistantReply(response)
                                    }
                                }
                            }
                        }
                    }
                }  else if (messageText == "save") {
                try {
                    // Prefer whatever you just "tried"
                    val jsonText: String = lastTriedJson ?: run {
                        val content = AIBackend.copyLast()
                        val extracted = content.substring(
                            content.indexOfFirst { it == '{' },
                            content.lastIndexOf('}') + 1
                        )
                        extracted
                    }

                    val json = JSONObject(jsonText)
                    val fileName = json.optString("name", "default") + ".json"

                    // Keep JSON in memory until user picks location
                    pendingContentToSave = json.toString(2)

                    // Launch system “Save As”
                    createFileLauncher.launch(fileName)
                    Toast.makeText(this, "Choose where to save $fileName", Toast.LENGTH_SHORT).show()

                } catch (e: Exception) {
                    Toast.makeText(this, "Error: content not valid JSON", Toast.LENGTH_SHORT).show()
                    Log.e("SAVE", "Failed to prepare save", e)
                }

                requestTextField.text.clear()

                } else if (messageText == "try") {
                    val content = AIBackend.copyLast()

                    // Strip code fences if present and trim
                    val cleaned = content
                        .replace("```json", "```")
                        .replace("```", "")
                        .trim()

                    // Safer extraction: take first '{'..matching '}' if you want; for now keep your approach
                    val start = cleaned.indexOf('{')
                    val end   = cleaned.lastIndexOf('}')
                    if (start == -1 || end == -1 || end <= start) {
                        Toast.makeText(this, "No JSON found in last response.", Toast.LENGTH_SHORT).show()
                        requestTextField.text.clear()
                        return@setOnClickListener
                    }
                    val jsonString = cleaned.substring(start, end + 1)

                    try {
                        // snapshot current session so we can "back"
                        val prevPrompt =
                            currentSystemPrompt
                                ?: (currentCharacterJson?.let { buildSystemPromptFromCharacterJson(it) })
                                ?: sharedInfo.getDataString("Setup").ifBlank { null }

                        preTrySnapshot = SessionSnapshot(
                            name = appName.text.toString(),
                            avatarUrl = currentAvatarUrl,
                            systemPrompt = prevPrompt,
                            messages = messages.toList(),
                            characterJson = currentCharacterJson
                        )

                        applyCharacterFromJson(jsonString)
                        lastTriedJson = jsonString
                        Toast.makeText(this, "Character applied. Type 'back' to undo or 'save' to export.", Toast.LENGTH_SHORT).show()

                    } catch (je: org.json.JSONException) {
                        Toast.makeText(this, "Invalid JSON.", Toast.LENGTH_SHORT).show()
                        Log.e("TRY_APPLY", "Invalid JSON", je)
                    } catch (e: Exception) {
                        // Don’t mislabel this as JSON error—surface the real cause
                        Toast.makeText(this, "Apply failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        Log.e("TRY_APPLY", "Failed to apply character", e)
                    }

                    requestTextField.text.clear()


                } else if (messageText == "back") {
                    val snap = preTrySnapshot
                    if (snap == null) {
                        Toast.makeText(this, "Nothing to go back to.", Toast.LENGTH_SHORT).show()
                        requestTextField.text.clear()
                    } else {
                        // Restore setup/system prompt
                        AIBackend.wipeChat(this)   // clear backend context
                        clearMessages()            // clears UI + backend again (safe double-clear)

                        val promptToRestore = snap.systemPrompt
                            ?: (snap.characterJson?.let { buildSystemPromptFromCharacterJson(it) })
                            ?: sharedInfo.getDataString("Setup").ifBlank { null }

                        promptToRestore?.let {
                            AIBackend.changeSetup(it)
                            currentSystemPrompt = it
                        } ?: run {
                            // As a last resort, clear to your default
                            AIBackend.resetSetup()
                            currentSystemPrompt = null
                        }

                        // Restore UI name
                        appName.text = snap.name

                        // Restore avatar
                        currentAvatarUrl = snap.avatarUrl
                        if (!currentAvatarUrl.isNullOrEmpty()) {
                            imageView.setImageTintList(null)
                            Picasso.get()
                                .load(currentAvatarUrl)
                                .fit()
                                .centerInside()
                                .into(imageView)
                        } else {
                            // optional: reset to a default/tinted icon if you have one
                            // imageView.setImageResource(R.drawable.ic_default_avatar)
                        }

                        // Restore chat messages + backend role log
                        for (m in snap.messages) {
                            messages.add(m)
                            AIBackend.addEntry(
                                if (m.isSentByCurrentUser) "user" else "assistant",
                                m.message
                            )
                        }
                        chatAdapter.notifyDataSetChanged()
                        recyclerView.scrollToPosition(messages.size - 1)

                        // Restore alternate greetings (from previous character JSON, if we had one)
                        snap.characterJson?.let { prevJson ->
                            try {
                                val data = JSONObject(prevJson).getJSONObject("data")
                                val name = data.getString("name")
                                val firstMes = data.optString("first_mes", null)
                                val alt = if (data.has("alternate_greetings"))
                                    data.getJSONArray("alternate_greetings")
                                else JSONArray()
                                Settings.loadAlternateGreetings(alt, name, firstMes)
                            } catch (e: Exception) {
                                Log.w("BACK", "Could not restore alternate greetings", e)
                            }
                        }

                        // Restore current-character bookkeeping
                        currentCharacterJson = snap.characterJson

                        Toast.makeText(
                            this,
                            "Reverted to previous character/session.",
                            Toast.LENGTH_SHORT
                        ).show()
                        preTrySnapshot = null // consume the undo
                        requestTextField.text.clear()
                    }

                }else {
                    val imageUris = pendingImageForMessageUris.toList()
                    val imageDataUrls = buildImageDataUrls(imageUris)

                    Toast.makeText(this, "Sending", Toast.LENGTH_SHORT).show()
                    addMessage(ChatMessage(messageText, true, imageUris.map { it.toString() }))
                    requestTextField.text.clear()
                    pendingImageForMessageUris.clear()

                    val responseCallback: (String) -> Unit = { response ->
                        if (response == "error") {
                            runOnUiThread {
                                Toast.makeText(this, "The request failed", Toast.LENGTH_LONG).show()
                                addMessage(ChatMessage("ERROR - please try again", false))
                                AIBackend.removeEntry()
                            }
                        } else {
                            if (isForeground) {
                                runOnUiThread { addMessage(ChatMessage(response, false)) }
                            } else {
                                // No UI work here; just persist
                                savePendingAssistantReply(response)
                                // (optional) post a notification so user knows it’s ready
                                postCompletionNotification(response.take(80))
                            }
                        }
                    }

                    if (imageDataUrls.isNotEmpty()) {
                        AIBackend.getResponse(this, messageText, imageDataUrls, responseCallback)
                    } else {
                        AIBackend.getResponse(this, messageText, responseCallback)
                    }
                }
            }
        }

        attachImageButton.setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }

        settingsButton.setOnClickListener {
            val intent = Intent(this@MainActivity, Settings::class.java)
            startActivityForResult(intent, REQUEST_CODE_IMAGE)
        }

        rerollButton.setOnClickListener {
            Toast.makeText(this, "Rerolling last message", Toast.LENGTH_SHORT).show()
            rerollLastMessage()
        }

    }


    // --- Foreground tracking + pending replies ---
    private var isForeground = false

    private fun savePendingAssistantReply(text: String) {
        val sp = getSharedPreferences("pending_replies", Context.MODE_PRIVATE)
        val list = sp.getStringSet("list", linkedSetOf())!!.toMutableSet()
        list += text
        sp.edit().putStringSet("list", list).apply()
    }

    private fun flushPendingAssistantReplies() {
        val sp = getSharedPreferences("pending_replies", Context.MODE_PRIVATE)
        val list = sp.getStringSet("list", linkedSetOf())!!.toMutableSet()
        if (list.isNotEmpty()) {
            list.forEach { reply ->
                // keep AI context in sync when you return to the app
                AIBackend.addEntry("assistant", reply)
                addMessage(ChatMessage(reply, false))
            }
            sp.edit().putStringSet("list", linkedSetOf()).apply()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                "ai_replies",
                "AI Replies",
                android.app.NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Notifications for AI responses" }

            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            mgr.createNotificationChannel(channel)
        }
    }

    private val requestNotifPermission =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) { granted ->
            // If user just granted, you can optionally post a queued notification here.
            // No-op is fine; we’ll attempt to notify on next message.
        }

    @SuppressLint("MissingPermission") // safe because we guard below
    private fun postCompletionNotification(preview: String) {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT) // bring existing instance forward

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, "ai_replies")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("AI reply ready")
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)     // keep tap action
            .setAutoCancel(true)

        NotificationManagerCompat.from(this)
            .notify(System.currentTimeMillis().toInt(), builder.build())
    }


    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // e.g., flush pending replies if you want
        flushPendingAssistantReplies()
    }













    // --- Single TextWatcher (prevents duplicates after selecting character/lorebook) ---
    private fun attachWatcherOnce(editText: EditText) {
        inputWatcher?.let { editText.removeTextChangedListener(it) }
        inputWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                textChanges.tryEmit(s?.toString().orEmpty())
            }
        }.also { editText.addTextChangedListener(it) }
    }

    // --- Debounced typing pipeline (does background work if added later, keeps main thread free) ---
    private fun startTypingPipeline(onQueryIO: suspend (String) -> Unit) {
        inputJob?.cancel()
        inputJob = lifecycleScope.launch {
            textChanges
                .debounce(180)
                .distinctUntilChanged()
                .mapLatest { query ->
                    withContext(Dispatchers.IO) {
                        onQueryIO(query)
                    }
                }
                .collect { /* no-op */ }
        }
    }

    private fun saveChatHistory() {
        val characterName = appName.text
        val input = EditText(this)
        AlertDialog.Builder(this)
            .setTitle("Save Chat As")
            .setMessage("Enter a name for this chat:")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val saveName = input.text.toString().ifEmpty { "default" }
                val fileName = "chat_history_${characterName}_$saveName.json"
                val chatHistoryFile = File(getExternalFilesDir(null), fileName)

                try {
                    FileWriter(chatHistoryFile).use { writer ->
                        val jsonArray = JSONArray()
                        for (message in messages) {
                            val jsonMessage = JSONObject()
                            jsonMessage.put("message", message.message)
                            jsonMessage.put("isSentByCurrentUser", message.isSentByCurrentUser)
                            jsonMessage.put("imageUris", JSONArray(message.imageUris))
                            jsonArray.put(jsonMessage)
                        }
                        writer.write(jsonArray.toString())
                    }
                    Toast.makeText(this, "Chat history saved!", Toast.LENGTH_SHORT).show()
                } catch (e: IOException) {
                    Toast.makeText(this, "Error saving chat history.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadChatHistory() {
        val files = getExternalFilesDir(null)?.listFiles { _, name ->
            name.startsWith("chat_history_${appName.text}_")
        } ?: arrayOf()

        if (files.isEmpty()) {
            Toast.makeText(this, "No saved chats found.", Toast.LENGTH_SHORT).show()
            return
        }

        val fileNames = files.map { it.name }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select Chat to Load")
            .setItems(fileNames) { _, which ->
                val file = files[which]
                try {
                    val jsonArray = JSONArray(FileReader(file).readText())
                    messages.clear()
                    AIBackend.wipeChat(this)

                    for (i in 0 until jsonArray.length()) {
                        val jsonMessage = jsonArray.getJSONObject(i)
                        val savedImageUris = mutableListOf<String>()
                        val imageArray = jsonMessage.optJSONArray("imageUris")
                        if (imageArray != null) {
                            for (j in 0 until imageArray.length()) {
                                imageArray.optString(j)?.takeIf { it.isNotBlank() }?.let { savedImageUris.add(it) }
                            }
                        } else {
                            jsonMessage.opt("imageUri")
                                ?.takeIf { it != JSONObject.NULL }
                                ?.toString()
                                ?.takeIf { it.isNotBlank() }
                                ?.let { savedImageUris.add(it) }
                        }

                        val message = ChatMessage(
                            jsonMessage.getString("message"),
                            jsonMessage.getBoolean("isSentByCurrentUser"),
                            savedImageUris
                        )
                        messages.add(message)
                        AIBackend.addEntry(
                            if (message.isSentByCurrentUser) "user" else "assistant",
                            message.message
                        )

                        if (!message.isSentByCurrentUser){
                            AIBackend.setLast(message.message)
                        }

                    }
                    chatAdapter.notifyDataSetChanged()
                    Toast.makeText(this, "Chat history loaded!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Error loading chat history.", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun deleteSavedChat() {
        val files = getExternalFilesDir(null)?.listFiles { _, name ->
            name.startsWith("chat_history_${appName.text}_")
        } ?: arrayOf()

        if (files.isEmpty()) {
            Toast.makeText(this, "No saved chats to delete.", Toast.LENGTH_SHORT).show()
            return
        }

        val fileNames = files.map { it.name }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select Chat to Delete")
            .setItems(fileNames) { _, which ->
                val file = files[which]
                AlertDialog.Builder(this)
                    .setTitle("Confirm Deletion")
                    .setMessage("Are you sure you want to delete \"${file.name}\"?")
                    .setPositiveButton("Delete") { _, _ ->
                        if (file.delete()) {
                            Toast.makeText(this, "Deleted ${file.name}", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "Failed to delete file.", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .show()
    }

    private fun rerollLastMessage() {
        val lastUserMessageIndex = messages.indexOfLast { it.isSentByCurrentUser }
        val lastAiMessageIndex = messages.indexOfLast { !it.isSentByCurrentUser }

        if (lastAiMessageIndex > lastUserMessageIndex && lastAiMessageIndex != -1) {
            messages.removeAt(lastAiMessageIndex)
            chatAdapter.notifyItemRemoved(lastAiMessageIndex)
            AIBackend.removeEntry()
            AIBackend.removeEntry()

            val userMessage = messages[lastUserMessageIndex].message
            AIBackend.getResponse(this, userMessage) { response ->
                runOnUiThread {
                    if (response != "error") {
                        addMessage(ChatMessage(response, false))
                    } else {
                        Toast.makeText(this, "Reroll failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            Toast.makeText(this, "No AI message to reroll", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE_IMAGE && resultCode == RESULT_OK) {
            val updatedImageUrl = data?.getStringExtra("updatedImageUrl")
            val newName = data?.getStringExtra("name")
            val firstMessage = data?.getStringExtra("firstMessage")

            Log.v("firstMessage", firstMessage.toString())
            if (!newName.isNullOrEmpty()) {
                appName.text = newName
                clearMessages() // NOTE: this triggers a range removal now (no full rebind)
            }

            if (firstMessage != null) {
                AIBackend.addEntry("assistant",
                    firstMessage.replace("{{user}}", "Stan").replace("{{char}}", appName.text.toString())
                )
                addMessage(
                    ChatMessage(
                        firstMessage.replace("{{user}}", "Stan").replace("{{char}}", appName.text.toString()),
                        false
                    )
                )
            }

            Log.v("updatedUrl", updatedImageUrl.toString())

            if (!updatedImageUrl.isNullOrEmpty()) {
                imageView.setImageTintList(null)
                // Size-aware decode avoids huge bitmaps on the main thread:
                Picasso.get()
                    .load(updatedImageUrl)
                    .fit()
                    .centerInside()
                    .into(imageView, object : com.squareup.picasso.Callback {
                        override fun onSuccess() {
                            Log.d("ImageLoad", "Image loaded successfully")
                        }
                        override fun onError(e: Exception?) {
                            Log.e("ImageLoadError", "Error loading image: ${e?.message}")
                        }
                    })
            }
        }
    }

    fun addMessage(message: ChatMessage) {
        messages.add(message)
        chatAdapter.notifyItemInserted(messages.size - 1)
        recyclerView.scrollToPosition(messages.size - 1)
    }

    // Avoid full rebind for clears (less jank):
    private fun clearMessages() {
        val count = messages.size
        if (count > 0) {
            messages.clear()
            chatAdapter.notifyItemRangeRemoved(0, count)
        }
        AIBackend.wipeChat(this)
    }

    private fun initializeUserSettings() {
        val nameInput = EditText(this)
        if (sharedInfo.exists("Name")) {
            AIBackend.changeName(sharedInfo.getDataString("Name"))
        } else {
            val nameDialog = AlertDialog.Builder(this)
                .setMessage("Please enter your name below:")
                .setView(nameInput)
                .setPositiveButton("Enter") { _, _ ->
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

    private fun applyCharacterFromJson(jsonString: String) {
        val json = try { JSONObject(jsonString) } catch (e: JSONException) {
            throw e // let caller show "Invalid JSON"
        }

        try {
            val data = json.getJSONObject("data")

            val name = data.getString("name")
            val rawDescription = data.getString("description")
            val firstMessage = data.optString("first_mes", "No first message provided.")
            val scenario = data.optString("scenario", "No scenario available.")
            val personality = data.optString("personality", "Nothing added, use the description")

            val userName = sharedInfo.getDataString("Name").ifEmpty { "User" }
            val description = rawDescription.replace("{{char}}", name).replace("{{user}}", userName)

            val systemPrompt = (
                    "You are a to act as $name. Always stay in character and keep your messages not too long but also not too short unless stated otherwise. " +
                            "If user sends a message with [] brackets, its an instruction for you not the character " +
                            "Here is the character you need to play: " +
                            ", You are $name, $description " +
                            ", Scenario: $scenario" +
                            ", Personality: $personality" +
                            ", you are to keep actions in asteriks and thoughts in '. Only respond as $name unless stated otherwise, " +
                            "never talk or decide things for the user! Make sure to be descriptive in your actions."
                    ).replace("\"", "\\\"").replace("\\r", "")

            AIBackend.changeSetup(systemPrompt)
            currentSystemPrompt = systemPrompt
            currentCharacterJson = jsonString

            // greetings
            if (data.has("alternate_greetings")) {
                Settings.loadAlternateGreetings(data.getJSONArray("alternate_greetings"), name, firstMessage)
            } else {
                Settings.loadAlternateGreetings(JSONArray(), name, firstMessage)
            }

            // UI + chat
            appName.text = name
            clearMessages()

            if (firstMessage.isNotEmpty()) {
                val shownFirst = firstMessage.replace("{{user}}", userName).replace("{{char}}", name)
                AIBackend.addEntry("assistant", shownFirst.replace("\"", "'"))
                addMessage(ChatMessage(shownFirst, false))
            }

            // Avatar (defensive)
            val avatarRaw = data.optString("avatar", "").trim()
            val safeAvatarUrl = avatarRaw.takeIf { url ->
                url.isNotBlank() &&
                        !url.equals("false", ignoreCase = true) &&
                        !url.equals("null", ignoreCase = true) &&
                        (url.startsWith("http://", true) ||
                                url.startsWith("https://", true) ||
                                url.startsWith("content://", true))
            }
            currentAvatarUrl = safeAvatarUrl
            if (safeAvatarUrl != null) {
                imageView.setImageTintList(null)
                runCatching {
                    Picasso.get().load(safeAvatarUrl).fit().centerInside().into(imageView)
                }.onFailure { e -> Log.e("ImageLoadError", "Avatar error: ${e.message}") }
            }
        } catch (e: Exception) {
            // Log but don’t rethrow; we already validated JSON. This prevents the outer catch from
            // incorrectly saying “Invalid JSON”.
            Log.e("APPLY_CHAR", "Non-JSON error applying character", e)
        }
    }




    private fun buildImageDataUrl(uri: Uri): String {
        val mimeType = contentResolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                MimeTypeMap.getFileExtensionFromUrl(uri.toString())
            )
            ?: "image/jpeg"

        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("Unable to read selected image")

        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return "data:$mimeType;base64,$base64"
    }

    private fun buildImageDataUrls(uris: List<Uri>): List<String> {
        val encoded = mutableListOf<String>()
        for (uri in uris) {
            runCatching { buildImageDataUrl(uri) }
                .onSuccess { encoded.add(it) }
                .onFailure {
                    Toast.makeText(this, "Couldn't attach one of the images.", Toast.LENGTH_SHORT).show()
                    Log.e("IMAGE_ATTACH", "Failed to encode image", it)
                }
        }
        return encoded
    }

    override fun onDestroy() {
        // Clean up any typing collectors/watchers to avoid leaks and stacked work:
        inputJob?.cancel()
        inputWatcher?.let {
            findViewById<EditText>(R.id.textInput)?.removeTextChangedListener(it)
        }
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        isForeground = true
        flushPendingAssistantReplies() // show anything that arrived while backgrounded
    }

    override fun onStop() {
        isForeground = false
        super.onStop()
    }

    private val imagePickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            pendingImageForMessageUris.addAll(uris)
            Toast.makeText(this, "${uris.size} image(s) attached.", Toast.LENGTH_SHORT).show()
        }
    }

    private val createFileLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null && pendingContentToSave != null) {
            saveJsonToUri(uri, pendingContentToSave!!)
            pendingContentToSave = null
        }
    }

    private var pendingContentToSave: String? = null

    private fun saveJsonToUri(uri: android.net.Uri, jsonText: String) {
        try {
            contentResolver.openOutputStream(uri)?.use { output ->
                output.write(jsonText.toByteArray())
            }
            Toast.makeText(this, "File saved!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error saving file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildSystemPromptFromCharacterJson(jsonString: String): String {
        val json = JSONObject(jsonString)
        val data = json.getJSONObject("data")

        val name = data.getString("name")
        val rawDescription = data.getString("description")
        val firstMessage = data.optString("first_mes", "No first message provided.")
        val scenario = data.optString("scenario", "No scenario available.")
        val personality = data.optString("personality", "Nothing added, use the description")
        val userName = sharedInfo.getDataString("Name").ifEmpty { "User" }
        val description = rawDescription.replace("{{char}}", name).replace("{{user}}", userName)

        return (
                "You are a to act as $name. Always stay in character and keep your messages not too long but also not too short unless stated otherwise. " +
                        "If user sends a message with [] brackets, its an instruction for you not the character " +
                        "Here is the character you need to play: " +
                        ", You are $name, $description " +
                        ", Scenario: $scenario" +
                        ", Personality: $personality" +
                        ", you are to keep actions in asteriks and thoughts in '. Only respond as $name unless stated otherwise, " +
                        "never talk or decide things for the user! Make sure to be descriptive in your actions."
                ).replace("\"", "\\\"").replace("\\r", "")
    }







    private val REQUEST_CODE_IMAGE = 100
}
