package com.spkdev.echomate

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Button
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.squareup.picasso.Picasso

import androidx.appcompat.widget.SearchView // Ensure correct import



class JsonPreviewActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var jsonAdapter: JsonAdapter
    private val jsonItems = mutableListOf<JsonItem>()

    companion object {
        const val REQUEST_CODE_OPEN_DIRECTORY = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_json_preview)

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        val selectFolderButton = findViewById<Button>(R.id.selectFolderButton)
        selectFolderButton.setOnClickListener {
            openFolderPicker()
        }

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        toolbar.setNavigationOnClickListener {
            finish()
        }

        // Set up the SearchView (correct usage of androidx.appcompat.widget.SearchView)
        val searchView = findViewById<SearchView>(R.id.searchView)

// Access the search icon view and modify its layout
        val searchIcon = searchView.findViewById<ImageView>(androidx.appcompat.R.id.search_mag_icon)

// Set the layout parameters to move the icon to the right side
        val layoutParams = searchIcon.layoutParams as LinearLayout.LayoutParams
        layoutParams.gravity = Gravity.END // Align the search icon to the right side
        layoutParams.marginStart = 0      // Remove any margin from the start (left side)
        layoutParams.marginEnd = 0        // Remove any margin from the end (right side)
        searchIcon.layoutParams = layoutParams

// Adjust the EditText area to take up the rest of the space
        val searchEditText = searchView.findViewById<EditText>(androidx.appcompat.R.id.search_src_text)
        val editTextLayoutParams = searchEditText.layoutParams as LinearLayout.LayoutParams
        editTextLayoutParams.weight = 1f  // Give the EditText as much space as possible
        searchEditText.layoutParams = editTextLayoutParams



        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)

                val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                val totalItemCount = layoutManager.itemCount
                val lastVisibleItem = layoutManager.findLastVisibleItemPosition()

                // Check if we need to load the next page
                if (lastVisibleItem >= totalItemCount - 1) {
                    // Defer the data update
                    recyclerView.post {
                        loadNextPage()
                    }
                }
            }
        })



// Set up the SearchView listener for query text change
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                jsonAdapter.filteredItems = if (newText.isNullOrEmpty()) {
                    jsonAdapter.items // Show all items if the query is empty
                } else {
                    jsonAdapter.items.filter { jsonItem ->
                        jsonItem.name.contains(newText, ignoreCase = true) ||
                                jsonItem.description.contains(newText, ignoreCase = true) ||
                                jsonItem.tags.any { tag -> tag.contains(newText, ignoreCase = true) }
                    }.toMutableList()
                }

                jsonAdapter.notifyDataSetChanged() // Notify the adapter to refresh
                return true
            }
        })






        // Load from SharedPreferences
        val savedUriString = getSharedPreferences("AppPreferences", MODE_PRIVATE)
            .getString("selectedFolderUri", null)

        savedUriString?.let {
            val savedUri = Uri.parse(it)
            loadJsonFilesFromFolder(savedUri)
        }
    }



    private fun openFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_CODE_OPEN_DIRECTORY)
    }

    private fun saveFolderUri(uri: Uri) {
        val sharedPreferences = getSharedPreferences("AppPreferences", MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        editor.putString("selectedFolderUri", uri.toString()) // Save URI as string
        editor.apply()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_OPEN_DIRECTORY && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                saveFolderUri(uri)  // Save the selected folder URI
                loadJsonFilesFromFolder(uri)
            }
        }
    }

    private var currentPage = 0
    private val pageSize = 300

    private fun loadJsonFilesFromFolder(folderUri: Uri) {
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

                if (documentName.endsWith(".json")) {
                    val jsonContent = readTextFromUri(documentUri)
                    val jsonItem = parseJsonToItem(jsonContent, documentName)
                    fileInfoList.add(Pair(lastModified, jsonItem))
                }
            }
        }

        fileInfoList.sortByDescending { it.first } // Sort by last modified
        val sortedJsonItems = fileInfoList.map { it.second }

        jsonItems.clear()
        jsonItems.addAll(sortedJsonItems)

        loadNextPage() // Load the first page
    }

    private fun loadNextPage() {
        val startIndex = currentPage * pageSize
        if (startIndex >= jsonItems.size) return // No more items to load

        val endIndex = (startIndex + pageSize).coerceAtMost(jsonItems.size)
        val pageItems = jsonItems.subList(startIndex, endIndex)

        if (currentPage == 0) {
            // Initialize adapter and set the data for the first page
            jsonAdapter = JsonAdapter { jsonItem ->
                // Create and show the preview dialog
                val builder = AlertDialog.Builder(this)
                val view = layoutInflater.inflate(R.layout.dialog_item_preview, null)

                // Set the views in the dialog
                val imageView = view.findViewById<ImageView>(R.id.imageView)
                val textViewName = view.findViewById<TextView>(R.id.textViewName)
                val textViewCreatorNotes = view.findViewById<TextView>(R.id.textViewCreatorNotes)
                val buttonViewDetails = view.findViewById<Button>(R.id.buttonViewDetails)
                val buttonReturnJson = view.findViewById<Button>(R.id.buttonReturnJson)
                val backButton = view.findViewById<ImageButton>(R.id.buttonBack)

                // Set the data for the dialog
                textViewName.text = jsonItem.name
                textViewCreatorNotes.text = jsonItem.creatorNotes

                // Load the image using Picasso
                Picasso.get()
                    .load(jsonItem.imageUrl) // image URL from the item
                    .into(imageView)

                // Set an action for the "View Details" button
                buttonViewDetails.setOnClickListener {
                    val intent = Intent(this, DetailsActivity::class.java)
                    intent.putExtra("name", jsonItem.name)
                    intent.putExtra("description", jsonItem.description)
                    intent.putExtra("jsonObject", jsonItem.jsonObject)
                    startActivity(intent)
                }

                // Set an action for the "Return JSON" button
                buttonReturnJson.setOnClickListener {
                    val resultIntent = Intent()
                    resultIntent.putExtra("selectedJson", jsonItem.jsonObject)
                    resultIntent.putExtra("characterName", jsonItem.name)
                    setResult(Activity.RESULT_OK, resultIntent)
                    finish() // Close the dialog and return to previous activity
                }

                backButton.setOnClickListener {
                    builder.create().dismiss() // Dismiss the dialog
                }

                // Create the dialog instance and show it
                val dialog = builder.setView(view)
                    .setCancelable(true) // Allows closing by tapping outside the dialog
                    .create()

                dialog.show()
            }
            recyclerView.adapter = jsonAdapter
            jsonAdapter.updateItems(pageItems) // Pass the first page's data to the adapter
        } else {
            // Add items to existing adapter for subsequent pages
            Log.d("Loading", "Loading next page")
            jsonAdapter.addItems(pageItems)
        }

        currentPage++
    }






    private fun readTextFromUri(uri: Uri): String {
        val inputStream = contentResolver.openInputStream(uri) ?: return ""
        return BufferedReader(InputStreamReader(inputStream)).use { it.readText() }
    }

    private fun parseJsonToItem(json: String, jsonName: String): JsonItem {
        try {
            val jsonObject = JSONObject(json)
            val dataObject = jsonObject.getJSONObject("data")
            val description = dataObject.optString("description", "No description available")
            val creatorNotes = dataObject.optString("creator_notes", "No creator notes available")
            val imageUrl = dataObject.optString("avatar", "No image URL available")
            val name = dataObject.optString("name", jsonName)
            val tagsArray = dataObject.optJSONArray("tags")


            val tags = mutableListOf<String>()

            if (tagsArray != null) {
                for (i in 0 until tagsArray.length()) {
                    tags.add(tagsArray.optString(i, ""))  // Add each tag to the list
                }
            }

            Log.v("tags", tagsArray.toString())
            return JsonItem(
                name = name,
                description = description,
                imageUrl = imageUrl,
                creatorNotes = creatorNotes,
                jsonObject = json,
                tags = tags
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return JsonItem(
                name = jsonName,
                description = "Invalid JSON format",
                imageUrl = "",
                creatorNotes = "",
                jsonObject = json,
                tags = listOf("")
            )
        }
    }
}
