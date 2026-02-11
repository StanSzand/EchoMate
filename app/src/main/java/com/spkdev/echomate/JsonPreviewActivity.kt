package com.spkdev.echomate

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.squareup.picasso.Picasso
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

class JsonPreviewActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var jsonAdapter: JsonAdapter
    private lateinit var searchView: SearchView
    private lateinit var toolbar: Toolbar

    private val allJsonItems = mutableListOf<JsonItem>()
    private var filterJob: Job? = null

    companion object {
        const val REQUEST_CODE_OPEN_DIRECTORY = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_json_preview)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        jsonAdapter = JsonAdapter { jsonItem -> showPreviewDialog(jsonItem) }
        recyclerView.adapter = jsonAdapter

        searchView = findViewById(R.id.searchView)
        setupSearch()

        findViewById<Button>(R.id.selectFolderButton).setOnClickListener {
            openFolderPicker()
        }

        val savedUriString = getSharedPreferences("AppPreferences", MODE_PRIVATE)
            .getString("selectedFolderUri", null)

        if (savedUriString != null) {
            loadJsonFilesFromFolder(Uri.parse(savedUriString))
        }
    }

    private fun setupSearch() {
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = true

            override fun onQueryTextChange(newText: String?): Boolean {
                filterJob?.cancel()
                filterJob = lifecycleScope.launch {
                    delay(120)
                    val filtered = withContext(Dispatchers.Default) {
                        filterItems(newText.orEmpty())
                    }
                    jsonAdapter.submitItems(filtered)
                }
                return true
            }
        })
    }

    private fun filterItems(query: String): List<JsonItem> {
        if (query.isBlank()) return allJsonItems
        return allJsonItems.filter { jsonItem ->
            jsonItem.name.contains(query, ignoreCase = true) ||
                jsonItem.description.contains(query, ignoreCase = true) ||
                jsonItem.tags.any { tag -> tag.contains(query, ignoreCase = true) }
        }
    }

    private fun showPreviewDialog(jsonItem: JsonItem) {
        val view = layoutInflater.inflate(R.layout.dialog_item_preview, null)
        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(true)
            .create()

        val imageView = view.findViewById<ImageView>(R.id.imageView)
        val textViewName = view.findViewById<TextView>(R.id.textViewName)
        val textViewCreatorNotes = view.findViewById<TextView>(R.id.textViewCreatorNotes)
        val buttonViewDetails = view.findViewById<Button>(R.id.buttonViewDetails)
        val buttonReturnJson = view.findViewById<Button>(R.id.buttonReturnJson)
        val backButton = view.findViewById<ImageButton>(R.id.buttonBack)

        textViewName.text = jsonItem.name
        textViewCreatorNotes.text = jsonItem.creatorNotes

        Picasso.get()
            .load(jsonItem.imageUrl)
            .placeholder(android.R.drawable.ic_menu_gallery)
            .error(android.R.drawable.ic_menu_report_image)
            .fit()
            .centerCrop()
            .into(imageView)

        buttonViewDetails.setOnClickListener {
            val intent = Intent(this, DetailsActivity::class.java)
            intent.putExtra("name", jsonItem.name)
            intent.putExtra("description", jsonItem.description)
            intent.putExtra("jsonUri", jsonItem.jsonUri)
            startActivity(intent)
        }

        buttonReturnJson.setOnClickListener {
            val resultIntent = Intent()
            resultIntent.putExtra("selectedJsonUri", jsonItem.jsonUri)
            resultIntent.putExtra("characterName", jsonItem.name)
            setResult(Activity.RESULT_OK, resultIntent)
            dialog.dismiss()
            finish()
        }

        backButton.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun openFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_CODE_OPEN_DIRECTORY)
    }

    private fun saveFolderUri(uri: Uri) {
        getSharedPreferences("AppPreferences", MODE_PRIVATE)
            .edit()
            .putString("selectedFolderUri", uri.toString())
            .apply()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_OPEN_DIRECTORY && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                saveFolderUri(uri)
                loadJsonFilesFromFolder(uri)
            }
        }
    }

    private fun loadJsonFilesFromFolder(folderUri: Uri) {
        lifecycleScope.launch {
            val parsedItems = withContext(Dispatchers.IO) {
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    folderUri,
                    DocumentsContract.getTreeDocumentId(folderUri)
                )

                val fileInfoList = mutableListOf<Pair<Long, JsonItem>>()

                contentResolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED
                    ),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val documentId = cursor.getString(0)
                        val documentName = cursor.getString(1)
                        val lastModified = cursor.getLong(2)
                        val documentUri = DocumentsContract.buildDocumentUriUsingTree(folderUri, documentId)

                        if (documentName.endsWith(".json", ignoreCase = true)) {
                            val jsonItem = parseJsonToItem(documentUri, documentName)
                            fileInfoList.add(Pair(lastModified, jsonItem))
                        }
                    }
                }

                fileInfoList
                    .sortedByDescending { it.first }
                    .map { it.second }
            }

            allJsonItems.clear()
            allJsonItems.addAll(parsedItems)
            jsonAdapter.submitItems(parsedItems)

            if (parsedItems.isEmpty()) {
                Toast.makeText(this@JsonPreviewActivity, "No JSON files found.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun parseJsonToItem(uri: Uri, jsonName: String): JsonItem {
        return try {
            val jsonObject = JSONObject(readTextFromUri(uri))
            val dataObject = jsonObject.optJSONObject("data")

            val description = dataObject?.optString("description", "No description available")
                ?: "No description available"
            val creatorNotes = dataObject?.optString("creator_notes", "No creator notes available")
                ?: "No creator notes available"
            val imageUrl = dataObject?.optString("avatar", "") ?: ""
            val name = dataObject?.optString("name", jsonName) ?: jsonName
            val tagsArray = dataObject?.optJSONArray("tags")

            val tags = mutableListOf<String>()
            if (tagsArray != null) {
                for (i in 0 until tagsArray.length()) {
                    tags.add(tagsArray.optString(i, ""))
                }
            }

            JsonItem(
                name = name,
                description = description,
                imageUrl = imageUrl,
                creatorNotes = creatorNotes,
                tags = tags,
                jsonUri = uri.toString()
            )
        } catch (e: Exception) {
            JsonItem(
                name = jsonName,
                description = "Invalid JSON format",
                imageUrl = "",
                creatorNotes = "",
                tags = emptyList(),
                jsonUri = uri.toString()
            )
        }
    }

    private fun readTextFromUri(uri: Uri): String {
        val inputStream = contentResolver.openInputStream(uri) ?: return ""
        return BufferedReader(InputStreamReader(inputStream)).use { it.readText() }
    }
}
