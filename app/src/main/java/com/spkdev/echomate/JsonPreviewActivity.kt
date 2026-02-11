package com.spkdev.echomate

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import android.app.Activity
import android.app.AlertDialog
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.squareup.picasso.Picasso
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class JsonPreviewActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var jsonAdapter: JsonAdapter
    private val jsonItems = mutableListOf<JsonItem>()

    companion object {
        const val REQUEST_CODE_OPEN_DIRECTORY = 1
    }

    private var currentPage = 0
    private val pageSize = 50
    private var showingFavs = false
    private var isSearching = false // NEW: block pagination while searching


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_json_preview)

        recyclerView = findViewById(R.id.recyclerView)

        // Grid: dynamic span so more tiles fit on wider screens
        val density = resources.displayMetrics.density
        val minTileDp = 160
        val spanCount = maxOf(2, (resources.displayMetrics.widthPixels / (minTileDp * density)).toInt())
        recyclerView.layoutManager = GridLayoutManager(this, spanCount)
        recyclerView.setHasFixedSize(true)

        // import com.google.android.material.appbar.MaterialToolbar
        val toolbar = findViewById<Toolbar>(R.id.toolbar)

// DO NOT call setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

// If you did not set app:menu in XML, inflate it here:
        if (toolbar.menu.size() == 0) {
            toolbar.inflateMenu(R.menu.menu_characters)
        }

        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_select_folder -> { openFolderPicker(); true }
                R.id.action_favs -> {
                    showingFavs = !showingFavs
                    isSearching = false // reset search state when toggling favs
                    jsonAdapter.filteredItems = if (showingFavs) {
                        jsonAdapter.items.filter { sharedInfo.isFavorite(it.name) }.toMutableList()
                    } else {
                        // back to paged view: reset pages and show first page again
                        currentPage = 0
                        mutableListOf<JsonItem>().apply {
                            addAll(pagedSlice(jsonAdapter.items, currentPage, pageSize))
                        }
                    }
                    jsonAdapter.notifyDataSetChanged()
                    true
                }
                R.id.action_search -> {
                    val sv = item.actionView as androidx.appcompat.widget.SearchView
                    sv.queryHint = "Search by Name or Notes"
                    sv.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
                        override fun onQueryTextSubmit(query: String?) = true
                        override fun onQueryTextChange(newText: String?): Boolean {
                            val q = newText?.trim().orEmpty()
                            isSearching = q.isNotEmpty()
                            // Search ACROSS ALL ITEMS
                            jsonAdapter.filteredItems = if (q.isEmpty()) {
                                // back to paged view
                                showingFavs = false
                                currentPage = 0
                                mutableListOf<JsonItem>().apply {
                                    addAll(pagedSlice(jsonAdapter.items, currentPage, pageSize))
                                }
                            } else {
                                jsonAdapter.items.filter {
                                    it.name.contains(q, true) ||
                                            it.description.contains(q, true) ||
                                            it.tags.any { t -> t.contains(q, true) }
                                }.toMutableList()
                            }
                            jsonAdapter.notifyDataSetChanged()
                            return true
                        }
                    })
                    true
                }
                else -> false
            }
        }



        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(rv, dx, dy)
                if (isSearching || showingFavs) return // NEW: don't paginate during these modes
                val lm = recyclerView.layoutManager as GridLayoutManager
                if (lm.findLastVisibleItemPosition() >= lm.itemCount - 1) {
                    recyclerView.post { loadNextPage() }
                }
            }
        })




        // Load previously picked folder (if permission is still valid)
        val savedUriString = getSharedPreferences("AppPreferences", MODE_PRIVATE)
            .getString("selectedFolderUri", null)

        savedUriString?.let {
            val savedUri = Uri.parse(it)
            val hasPermission = contentResolver.persistedUriPermissions.any { p ->
                p.uri == savedUri && p.isReadPermission
            }
            if (hasPermission) {
                lifecycleScope.launch {
                    val items = loadJsonItemsFromFolderInBackground(savedUri)
                    jsonItems.clear()
                    jsonItems.addAll(items)
                    currentPage = 0
                    loadNextPage()
                }
            } else {
                getSharedPreferences("AppPreferences", MODE_PRIVATE)
                    .edit().remove("selectedFolderUri").apply()
            }
        }
    }

    // --- Background loader (add to JsonPreviewActivity) ---
    private suspend fun loadJsonItemsFromFolderInBackground(folderUri: android.net.Uri): List<JsonItem> =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
                folderUri, android.provider.DocumentsContract.getTreeDocumentId(folderUri)
            )
            val results = mutableListOf<Pair<Long, JsonItem>>()

            contentResolver.query(
                childrenUri,
                arrayOf(
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED
                ),
                null, null, null
            )?.use { cursor ->
                val idxId = cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val idxName = cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val idxMod = cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                while (cursor.moveToNext()) {
                    val documentId = cursor.getString(idxId)
                    val documentName = cursor.getString(idxName)
                    val lastModified = cursor.getLong(idxMod)
                    val docUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(folderUri, documentId)

                    if (documentName.endsWith(".json", ignoreCase = true)) {
                        val jsonContent = contentResolver.openInputStream(docUri)?.use { it.bufferedReader().readText() } ?: ""
                        // parseJsonToItem: use your existing parser for JsonItem
                        val item = parseJsonToItem(jsonContent, documentName)
                        results += lastModified to item
                    }
                }
            }
            results.sortedByDescending { it.first }.map { it.second }
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
            val items = loadJsonItemsFromFolderInBackground(folderUri) // PRELOAD ALL
            jsonItems.clear()
            jsonItems.addAll(items)
            currentPage = 0
            loadNextPage()
        }
    }

    private fun pagedSlice(all: List<JsonItem>, page: Int, size: Int): List<JsonItem> {
        val start = page * size
        if (start >= all.size) return emptyList()
        val end = (start + size).coerceAtMost(all.size)
        return all.subList(start, end)
    }




    private fun ensureAdapter() {
        if (::jsonAdapter.isInitialized) return

        jsonAdapter = JsonAdapter { jsonItem ->
            val view = layoutInflater.inflate(R.layout.dialog_item_preview, null)

            val imageView = view.findViewById<ImageView>(R.id.imageView)
            val textViewName = view.findViewById<TextView>(R.id.textViewName)
            val textViewCreatorNotes = view.findViewById<TextView>(R.id.textViewCreatorNotes)
            val buttonViewDetails = view.findViewById<android.widget.Button>(R.id.buttonViewDetails)
            val buttonReturnJson = view.findViewById<android.widget.Button>(R.id.buttonReturnJson)
            val backButton = view.findViewById<ImageButton>(R.id.buttonBack)
            val favButton = view.findViewById<ImageButton>(R.id.buttonFavorite)

            val dialog = AlertDialog.Builder(this)
                .setView(view)
                .setCancelable(true)
                .create()

            textViewName.text = jsonItem.name
            textViewCreatorNotes.text = jsonItem.creatorNotes
            if (jsonItem.imageUrl.isNotEmpty()) {
                Picasso.get().load(jsonItem.imageUrl).into(imageView)
            } else {
                imageView.setImageResource(R.drawable.placeholder)
            }

            updateFavoriteIcon(favButton, jsonItem.name)
            favButton.setOnClickListener {
                if (sharedInfo.isFavorite(jsonItem.name)) {
                    sharedInfo.removeFavorite(jsonItem.name)
                    Toast.makeText(this, "${jsonItem.name} removed from favorites", Toast.LENGTH_SHORT).show()
                } else {
                    sharedInfo.addFavorite(jsonItem.name)
                    Toast.makeText(this, "${jsonItem.name} added to favorites", Toast.LENGTH_SHORT).show()
                }
                updateFavoriteIcon(favButton, jsonItem.name)
            }

            buttonViewDetails.setOnClickListener {
                val intent = Intent(this, DetailsActivity::class.java)
                intent.putExtra("name", jsonItem.name)
                intent.putExtra("description", jsonItem.description)
                intent.putExtra("jsonObject", jsonItem.jsonObject)
                startActivity(intent)
            }

            buttonReturnJson.setOnClickListener {
                val resultIntent = Intent()
                resultIntent.putExtra("selectedJson", jsonItem.jsonObject)
                resultIntent.putExtra("characterName", jsonItem.name)
                setResult(RESULT_OK, resultIntent)
                dialog.dismiss()
                finish()
            }

            backButton.setOnClickListener { dialog.dismiss() }

            dialog.show()
        }

        recyclerView.adapter = jsonAdapter
    }



    private suspend fun loadPageContent(fileInfoList: List<Pair<Long, Uri>>, page: Int) {
        val startIndex = page * pageSize
        if (startIndex >= fileInfoList.size) return
        val endIndex = (startIndex + pageSize).coerceAtMost(fileInfoList.size)

        val pageFiles = fileInfoList.subList(startIndex, endIndex)

        val itemsOnPage = withContext(Dispatchers.IO) {
            pageFiles.map { (_, uri) ->
                val jsonContent = readTextFromUri(uri)
                parseJsonToItem(jsonContent, uri.lastPathSegment ?: "Unknown.json")
            }
        }

        jsonItems.addAll(itemsOnPage)

        ensureAdapter()
        if (page == 0) {
            jsonAdapter.updateItems(jsonItems)
        } else {
            jsonAdapter.addItems(itemsOnPage)
        }
        currentPage++

    }

    private fun loadNextPage() {
        val pageItems = pagedSlice(jsonItems, currentPage, pageSize)
        if (pageItems.isEmpty()) return

        ensureAdapter()

        if (currentPage == 0) {
            // 1) Tell adapter the FULL dataset so search sees everything
            jsonAdapter.setAllItems(jsonItems)
            // 2) Show only the first page
            jsonAdapter.filteredItems = pageItems.toMutableList()
            jsonAdapter.notifyDataSetChanged()
        } else {
            // append visible page
            jsonAdapter.showNextPage(pageItems)
        }
        currentPage++
    }


    private fun readTextFromUri(uri: Uri): String {
        val inputStream = contentResolver.openInputStream(uri) ?: return ""
        return BufferedReader(InputStreamReader(inputStream)).use { it.readText() }
    }

    private fun parseJsonToItem(json: String, jsonName: String): JsonItem {
        return try {
            val jsonObject = JSONObject(json)
            val dataObject = jsonObject.getJSONObject("data")
            val description = dataObject.optString("description", "No description available")
            val creatorNotes = dataObject.optString("creator_notes", "No creator notes available")
            val imageUrl = dataObject.optString("avatar", "")
            val name = dataObject.optString("name", jsonName)
            val tagsArray = dataObject.optJSONArray("tags")

            val tags = mutableListOf<String>()
            if (tagsArray != null) {
                for (i in 0 until tagsArray.length()) tags.add(tagsArray.optString(i, ""))
            }

            JsonItem(
                name = name,
                description = description,
                imageUrl = imageUrl,
                creatorNotes = creatorNotes,
                jsonObject = json,
                tags = tags
            )
        } catch (e: Exception) {
            JsonItem(
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

fun updateFavoriteIcon(button: ImageButton, name: String) {
    if (sharedInfo.isFavorite(name)) {
        button.setImageResource(R.drawable.ic_star_filled)
    } else {
        button.setImageResource(R.drawable.ic_star_outline)
    }
}
