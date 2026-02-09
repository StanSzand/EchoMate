package com.spkdev.echomate

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

class LorebookPreviewActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var jsonAdapter: JsonAdapter

    companion object {
        const val REQUEST_CODE_OPEN_DIRECTORY = 2
    }

    // full, preloaded dataset
    private val loreItems = mutableListOf<JsonItem>()

    // paging + UI state
    private var currentPage = 0
    private val pageSize = 300
    private var isSearching = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_json_preview)

        recyclerView = findViewById(R.id.recyclerView)

        val density = resources.displayMetrics.density
        val minTileDp = 160
        val spanCount = maxOf(2, (resources.displayMetrics.widthPixels / (minTileDp * density)).toInt())
        recyclerView.layoutManager = GridLayoutManager(this, spanCount)
        recyclerView.setHasFixedSize(true)

        // Toolbar (do NOT call setSupportActionBar)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        if (toolbar.menu.size() == 0) {
            toolbar.inflateMenu(R.menu.menu_characters) // folder + search
        }

        // Menu actions
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_select_folder -> {
                    openFolderPicker()
                    true
                }
                R.id.action_search -> {
                    val sv = item.actionView as androidx.appcompat.widget.SearchView
                    sv.queryHint = "Search lore by name or notes"
                    sv.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
                        override fun onQueryTextSubmit(query: String?) = true
                        override fun onQueryTextChange(newText: String?): Boolean {
                            val q = newText?.trim().orEmpty()
                            isSearching = q.isNotEmpty()
                            // Search ACROSS the full set (jsonAdapter.items)
                            if (q.isEmpty()) {
                                currentPage = 0
                                jsonAdapter.filteredItems = pagedSlice(jsonAdapter.items, currentPage, pageSize).toMutableList()
                                jsonAdapter.notifyDataSetChanged()
                            } else {
                                jsonAdapter.filteredItems = jsonAdapter.items.filter {
                                    it.name.contains(q, true) ||
                                            it.description.contains(q, true) ||
                                            it.creatorNotes.contains(q, true) ||
                                            it.tags.any { t -> t.contains(q, true) }
                                }.toMutableList()
                                jsonAdapter.notifyDataSetChanged()
                            }
                            return true
                        }
                    })
                    true
                }
                else -> false
            }
        }

        // Infinite scroll (disabled while searching)
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(rv, dx, dy)
                if (isSearching) return
                val lm = recyclerView.layoutManager as GridLayoutManager
                if (lm.findLastVisibleItemPosition() >= lm.itemCount - 1) {
                    recyclerView.post { loadNextPage() }
                }
            }
        })

        // Load saved lore folder if permission still valid
        val savedUriString = getSharedPreferences("AppPreferences", MODE_PRIVATE)
            .getString("selectedLoreFolderUri", null)

        savedUriString?.let {
            val savedUri = Uri.parse(it)
            val hasPermission = contentResolver.persistedUriPermissions.any { perm ->
                perm.uri == savedUri && perm.isReadPermission
            }
            if (hasPermission) {
                lifecycleScope.launch {
                    val items = loadLoreItemsFromFolderInBackground(savedUri)
                    loreItems.clear()
                    loreItems.addAll(items)
                    currentPage = 0
                    loadNextPage()
                }
            }
        }
    }

    private fun openFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_CODE_OPEN_DIRECTORY)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_OPEN_DIRECTORY && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                saveFolderUri(uri)
                lifecycleScope.launch {
                    val items = loadLoreItemsFromFolderInBackground(uri)
                    loreItems.clear()
                    loreItems.addAll(items)
                    currentPage = 0
                    loadNextPage()
                }
            }
        }
    }

    private fun saveFolderUri(uri: Uri) {
        getSharedPreferences("AppPreferences", MODE_PRIVATE)
            .edit()
            .putString("selectedLoreFolderUri", uri.toString())
            .apply()
    }

    // -------- Loading --------

    private suspend fun loadLoreItemsFromFolderInBackground(folderUri: Uri): List<JsonItem> =
        withContext(Dispatchers.IO) {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                folderUri, DocumentsContract.getTreeDocumentId(folderUri)
            )
            val results = mutableListOf<Pair<Long, JsonItem>>() // sort by lastModified desc

            contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
                ),
                null, null, null
            )?.use { cursor ->
                val idxId = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val idxName = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val idxMod = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                while (cursor.moveToNext()) {
                    val documentId = cursor.getString(idxId)
                    val documentName = cursor.getString(idxName)
                    val lastModified = cursor.getLong(idxMod)
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(folderUri, documentId)

                    if (documentName.endsWith(".json", ignoreCase = true)) {
                        val jsonContent = readTextFromUri(docUri)
                        val item = parseLoreJson(jsonContent, documentName, docUri)
                        results += lastModified to item
                    }
                }
            }

            results.sortedByDescending { it.first }.map { it.second }
        }

    private fun readTextFromUri(uri: Uri): String {
        val inputStream = contentResolver.openInputStream(uri) ?: return ""
        return BufferedReader(InputStreamReader(inputStream)).use { it.readText() }
    }

    // Lorebooks don’t have the same "data{...}" envelope — keep your original parse.
    private fun parseLoreJson(json: String, jsonName: String, fileUri: Uri): JsonItem {
        return try {
            val obj = JSONObject(json)
            val description = obj.optString("description", "")
            val imageUrl = obj.optString("avatar", "")
            val name = obj.optString("name", jsonName).ifEmpty { jsonName }
            JsonItem(
                name = name,
                description = description,
                imageUrl = imageUrl,
                creatorNotes = description,
                jsonObject = json,
                tags = emptyList(),
                fileUri = fileUri
            )
        } catch (e: Exception) {
            Log.e("LoreParse", "Error: $e")
            JsonItem(
                name = jsonName,
                description = "Invalid JSON",
                imageUrl = "",
                creatorNotes = "",
                jsonObject = json,
                tags = emptyList(),
                fileUri = fileUri
            )
        }
    }

    // -------- Paging --------

    private fun pagedSlice(all: List<JsonItem>, page: Int, size: Int): List<JsonItem> {
        val start = page * size
        if (start >= all.size) return emptyList()
        val end = (start + size).coerceAtMost(all.size)
        return all.subList(start, end)
    }

    private fun ensureAdapter() {
        if (::jsonAdapter.isInitialized) return

        jsonAdapter = JsonAdapter { jsonItem ->
            // Selecting a lorebook sets its file path in the backend
            AIBackend.setLorePath(jsonItem.fileUri.toString())
            finish()
        }
        recyclerView.adapter = jsonAdapter
    }

    private fun loadNextPage() {
        val pageItems = pagedSlice(loreItems, currentPage, pageSize)
        if (pageItems.isEmpty()) return

        ensureAdapter()

        if (currentPage == 0) {
            // 1) Give adapter the FULL dataset so search works across all
            jsonAdapter.setAllItems(loreItems)
            // 2) Show only page 1
            jsonAdapter.filteredItems = pageItems.toMutableList()
            jsonAdapter.notifyDataSetChanged()
        } else {
            jsonAdapter.showNextPage(pageItems)
        }
        currentPage++
    }
}
