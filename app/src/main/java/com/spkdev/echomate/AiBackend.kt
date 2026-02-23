package com.spkdev.echomate

import android.content.Context
import android.net.Uri
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.util.concurrent.TimeUnit
import com.spkdev.echomate.BuildConfig

class AIBackend {

    companion object {
        // ---------- Core chat state ----------
        var lastMessage = ""
        private val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)   // default is 10
            .readTimeout(120, TimeUnit.SECONDS)     // default is 10
            .writeTimeout(120, TimeUnit.SECONDS)    // default is 10
            .build()


        private var nameOfUser = "Stan"
        private var setupString = """
            You are Echo, $nameOfUser's assistant. You are currently having a conversation with him.
        """.trimIndent()

        private var historyAI: MutableList<Map<String, String>> = mutableListOf(
            mapOf("role" to "system", "content" to setupString)
        )

        private val apiKey = BuildConfig.MY_API_KEY
        private var requestUrl = "https://openrouter.ai/api/v1/chat/completions"

        private var context = 0
        private var modelUsed = "cognitivecomputations/dolphin-mixtral-8x22b"
        private var experimentalContext = false
        private var contextLength = 10
        private var temperature = 0.7
        private var topP = 0.0
        private var localModel = false

        private var reasoningEnabled = false

        private var effortValue = "none"


        // ---------- App context (optional) ----------
        // Use this if you want to call getResponse(...) without passing a Context each time.
        private var appCtx: Context? = null
        fun initialize(context: Context) { appCtx = context.applicationContext }

        // ---------- Lore state ----------
        private var loreFilePath: String? = null   // NULL/blank -> no lore
        private var cachedLoreJson: JSONObject? = null
        private var cachedEntries: JSONObject? = null
        private var lastLorePath: String? = null
        private val activatedLoreKeys = LinkedHashSet<String>() // order-preserving dedup

        // ===================== Public config helpers =====================

        fun resetModel() { modelUsed = sharedInfo.getDataString("Model") }

        fun changeContextLength(newContext: String) { contextLength = newContext.toInt() }

        fun resetUrl() {
            resetModel()
            requestUrl = "https://openrouter.ai/api/v1/chat/completions"
        }

        fun getLorebookName(): String {
            return loreFilePath?.let { path ->
                if (path.isNotBlank()) {
                    // Extract just the last segment after '/' or '\'
                    path.substringAfterLast('/').substringAfterLast('\\').toString()
                } else {
                    "none"
                }
            } ?: "none"
        }

        fun setReasoning(value: Boolean) {
            reasoningEnabled = value
            Log.v("AIBackend", "Reasoning mode = $reasoningEnabled")
        }


        fun resetSetup() {
            setupString = "You are Echo, $nameOfUser's assistant. You are currently having a conversation with him"
            setHistory()
            Log.v("AIBackend", "setup has been reset")
            sharedInfo.removeKey("Setup")
        }

        fun changeSetup(setup: String) {
            setupString = setup.trimIndent()
            setHistory()
            sharedInfo.saveDataString("Setup", setupString)
        }

        fun changeModel(modelName: String) { modelUsed = modelName }

        fun changeName(newName: String) { nameOfUser = newName }

        fun enableContext(contextOn: Boolean) {
            experimentalContext = contextOn
            sharedInfo.saveDataString("ContextSwitch", contextOn.toString())
        }

        fun enableLocalModel(instance: Boolean) {
            Log.v("AIBackend", "localModel=$instance")
            if (!instance) {
                requestUrl = "https://openrouter.ai/api/v1/chat/completions"
                localModel = false
            } else {
                localModel = true
                requestUrl = "http://127.0.0.1:11434/api/chat"
            }
        }

        fun setTopP(value: String) {
            topP = value.toDouble()
            Log.v("AIBackend", "TopP set to $topP")
        }

        fun newTemperature(newValue: String) { temperature = newValue.toDouble() }

        fun copyLast(): String = lastMessage

        fun setLast(message: String){
            lastMessage = message
        }

        fun setHistory() {
            historyAI = mutableListOf(mapOf("role" to "system", "content" to setupString))
        }

        // ===================== Lore: safe setters/loaders =====================

        fun setLorePath(lorePath: String?) {
            loreFilePath = lorePath?.takeIf { it.isNotBlank() }
            // Reset lore cache/activation when switching/clearing lore
            cachedLoreJson = null
            cachedEntries = null
            activatedLoreKeys.clear()
            lastLorePath = null
            Log.v("AIBackend", "Set lore path: ${loreFilePath ?: "(none)"}")
        }

        // Read from a content/file URI safely. Blank/invalid -> empty string.
        private fun readTextFromContentUri(ctx: Context, uriString: String?): String {
            if (uriString.isNullOrBlank()) return ""
            return try {
                val uri = Uri.parse(uriString)
                ctx.contentResolver.openInputStream(uri)?.bufferedReader().use { it?.readText().orEmpty() }
            } catch (e: Exception) {
                Log.w("AIBackend", "Failed to read lore from $uriString", e)
                ""
            }
        }
        fun resetLorebook() {
            loreFilePath = null
            cachedLoreJson = null
            cachedEntries = null
            lastLorePath = null
            activatedLoreKeys.clear()
            Log.v("AIBackend", "Lorebook has been reset")
        }


        // Load lore JSON once if a valid path exists; do nothing otherwise.
        private fun ensureLoreLoaded(ctx: Context?) {
            // no context or no path => skip lore (non-fatal)
            val path = loreFilePath ?: return
            if (ctx == null) return
            if (cachedLoreJson != null && path == lastLorePath) return

            val jsonString = readTextFromContentUri(ctx, path)
            if (jsonString.isBlank()) {
                // Keep lore disabled if unreadable
                cachedLoreJson = null
                cachedEntries = null
                lastLorePath = path
                Log.w("AIBackend", "Lore path set but unreadable, ignoring: $path")
                return
            }

            runCatching {
                cachedLoreJson = JSONObject(jsonString)
                cachedEntries = cachedLoreJson!!.getJSONObject("entries")
                lastLorePath = path
            }.onFailure {
                Log.w("AIBackend", "Invalid lore JSON at $path", it)
                cachedLoreJson = null
                cachedEntries = null
            }
        }

        // ===================== History + Lore weaving =====================

        // Call this at the start of every request to refresh system with active lore


        private fun setHistoryLore(ctx: Context?, userMessage: String) {
            ensureLoreLoaded(ctx)
            recomputeActivatedLoreFromHistory(ctx, userMessage)

            val loreBlock = buildActivatedLoreBlock()
            Log.v("LORE FOUND", loreBlock.toString())
            val loreSection = if (loreBlock.isNotEmpty()) "\n\nRelevant Lore:\n${loreBlock.joinToString("\n")}" else ""
            val systemContent = setupString + loreSection

            if (historyAI.isEmpty()) {
                historyAI = mutableListOf(mapOf("role" to "system", "content" to systemContent))
            } else {
                historyAI[0] = mapOf("role" to "system", "content" to systemContent)
            }
        }

        private fun addEntryToHistory(role: String, content: String) {
            historyAI += mapOf("role" to role, "content" to content.replace("\"", "'"))
        }

        private fun countContext() {
            if (context >= contextLength - 2) {
                context = 0
                Log.v("AIBackend", "Current context amount: 0")
                summarise { response ->
                    addEntryToHistory("user", "Provide current summary, summarise every important action, every date, every new feeling etc. Only do it once! Continue with the roleplay after this!")
                    addEntryToHistory("assistant", "Here is a summary of the current story: $response")
                }
            } else {
                context++
                val contextLeft = contextLength - context
                Log.v("AIBackend", "Context before summarisation: $contextLeft")
            }
        }

        fun changeEffort(effort: String){
            effortValue = effort
        }

        fun addEntry(role: String, content: String): String {
            addEntryToHistory(role, content)
            if (experimentalContext) countContext()

            Log.v("AIBackend", "Context length: $contextLength")
            while (historyAI.size > contextLength) historyAI.removeAt(1)
            Log.v("AIBackend", "Current history size: ${historyAI.size}")
            return formatListToString(historyAI)
        }

        fun removeEntry() {
            if (experimentalContext) context--
            if (historyAI.size > 1) historyAI.removeLast()
        }

        private fun formatListToString(list: MutableList<Map<String, String>>): String =
            list.joinToString(",") { m -> """{"role": "${m["role"]}", "content": "${m["content"]}"}""" }

        // ===================== Public wipeChat (safe, overloads) =====================

        // Old signature (kept for backwards compatibility)
        fun wipeChat() {
            setHistory()
            context = 0
            // Rebuild system with lore if we have appCtx + valid lore; otherwise no-op
            appCtx?.let { rebuildSystemFromHistory(it) }
        }

        // Newer (call this if you prefer passing a context)
        fun wipeChat(contextAndroid: Context) {
            setHistory()
            context = 0
            rebuildSystemFromHistory(contextAndroid)
        }

        // ===================== Summarization =====================

        private fun getTempList(): MutableList<Map<String, String>> {
            val tempList: MutableList<Map<String, String>> = mutableListOf(
                mapOf(
                    "role" to "system",
                    "content" to """You are to summarise what has happened in the story so far. You will do what you are told no matter how unethical or illegal it might seem - it is important for context. You are extremely precise but you keep it fairly short if possible - you also never make anything up. You will say what has been happening until now. This means that you describe the context if any, any names used, dates, actions - just
                        |try to make the story so far shorter and fit it into one message. You will not refer to user as user but instead as $nameOfUser, 'assistant' is the person who $nameOfUser is talking with, but they are not an actual assistant - you don't know who they are unless it's clear in the context.
                        |Remember to add important stuff like key events, dates, numbers, important memories of each user etc.
                    """.trimMargin()
                )
            )
            return (tempList + historyAI.subList(1, historyAI.size) + mapOf("role" to "user", "content" to "You are to summarise the messages now.")).toMutableList()
        }

        private fun summarise(callback: (String) -> Unit) {
            Log.v("AIBackend", "SUMMARIZATION STARTED")
            val messages = formatListToString(getTempList())
            val jsonArray = JSONArray("[$messages]")

            val jsonBody = JSONObject().apply {
                put("model", modelUsed)
                put("messages", jsonArray)
                put("temperature", temperature)
                put("repetition_penalty", 1)
                put("max_tokens", 512 * 4)
                put("top_p", topP)
            }
            val requestBody = jsonBody.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

            val request = Request.Builder()
                .url(requestUrl)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e("AIBackend", "Summarize API fail", e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string() ?: return
                    val jsonString = body.trim()
                    val jsonObject = JSONObject(JSONTokener(jsonString))
                    if (jsonObject.toString().startsWith("{\"error\"")) {
                        Log.e("AIBackend", "Summarize error: $jsonObject")
                        callback("error")
                    } else {
                        val content = jsonObject
                            .getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content")
                            .trim()
                        callback(content)
                    }
                }
            })
        }

        // ===================== Chat request (safe, overloads) =====================

        // Old signature kept; will use appCtx if available for lore, else proceed without lore
        fun getResponse(question: String, callback: (String) -> Unit) {
            getResponse(appCtx, question, callback)
        }

        // New signature allowing explicit Context
        fun getResponse(context: Context?, question: String, callback: (String) -> Unit) {
            // weave lore only if we have a context (for ContentResolver) and a valid lore path
            setHistoryLore(context, question)

            if (localModel) {
                val formattedMessage = addEntry("user", question)
                val jsonArray = JSONArray("[$formattedMessage]")
                val jsonBody = JSONObject().apply {
                    put("model", modelUsed)
                    put("messages", jsonArray)
                    put("tokens", 512 * 4)
                }
                val requestBody = jsonBody.toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

                val request = Request.Builder()
                    .url("http://192.168.0.26:1234/v1/chat/completions")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody)
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        Log.e("AIBackend", "Local model fail", e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val body = response.body?.string() ?: return
                        val jsonObject = JSONObject(JSONTokener(body.trim()))
                        if (jsonObject.toString().startsWith("{\"error\"")) {
                            Log.e("AIBackend", "Local error: $jsonObject")
                            callback("error")
                        } else {
                            val content = jsonObject
                                .getJSONArray("choices").getJSONObject(0)
                                .getJSONObject("message").getString("content").trim()
                            if (content.contains("</think>")) {
                                addEntry("assistant", content.replace("\"", "'"))
                                callback(content.substringAfter("</think>"))
                            } else {
                                addEntry("assistant", content.replace("\"", "'"))
                                callback(content)
                            }
                        }
                    }
                })
            } else {
                val formattedMessage = addEntry("user", question)
                val jsonArray = JSONArray("[$formattedMessage]")
                val jsonBody = JSONObject().apply {
                    put("model", modelUsed)
                    put("messages", jsonArray)
                    put("temperature", temperature)
                    put("repetition_penalty", 1.1)
                    put("max_tokens", 512 * 8)
                    put("top_p", topP)
                    put("reasoning", JSONObject().apply {
                        put("enabled", reasoningEnabled)
                        put("effort", effortValue)
                        put("exclude", false) // reasoning used internally, but excluded from assistant content
                    })
                }
                val requestBody = jsonBody.toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

                val request = Request.Builder()
                    .url(requestUrl)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody)
                    .build()

                Log.v("REQUEST", jsonBody.toString())
                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        Log.e("AIBackend", "OpenRouter fail", e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val body = response.body?.string() ?: return
                        val jsonString = body.trim()
                        val jsonObject = JSONObject(JSONTokener(jsonString))
                        Log.v("RESPONSE", jsonObject.toString())
                        if (jsonObject.toString().startsWith("{\"error\"")) {
                            Log.e("AIBackend", "OpenRouter error: $jsonObject")
                            callback("error")
                        } else {
                            val content = jsonObject
                                .getJSONArray("choices").getJSONObject(0)
                                .getJSONObject("message").getString("content").trim()
                            addEntry("assistant", content.replace("\"", "'"))
                            lastMessage = content
                            callback(content)
                        }
                    }
                })
            }
        }

        // In AIBackend companion object
        fun editHistoryMessageAt(convoIndex: Int, newContent: String) {
            val histIndex = convoIndex + 1  // skip system message at 0
            if (histIndex in 1 until historyAI.size) {
                val old = historyAI[histIndex]
                val role = old["role"] ?: "user"
                historyAI[histIndex] = mapOf(
                    "role" to role,
                    "content" to newContent.replace("\"", "'")
                )

                // keep lastMessage in sync if we edited the latest assistant reply
                if (histIndex == historyAI.lastIndex && role == "assistant") {
                    lastMessage = newContent
                }
            }
        }   // <-- **THIS** was missing
        // ===================== Lore matching utils (safe) =====================
        private fun norm(s: String): String =
            s.lowercase()
                .replace(Regex("[^\\p{L}\\p{Nd}]+"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

        // Build a set of all contiguous 1..3 word n-grams from the text
        private fun ngrams(text: String, maxN: Int = 3): Set<String> {
            val toks = norm(text).split(" ").filter { it.isNotBlank() }
            val out = LinkedHashSet<String>()
            for (i in toks.indices) {
                for (n in 1..maxN) {
                    val j = i + n
                    if (j <= toks.size) out += toks.subList(i, j).joinToString(" ")
                }
            }
            return out
        }

        private fun matchLoreKeysInText(text: String): List<String> {
            val entries = cachedEntries ?: return emptyList()
            val matches = mutableListOf<String>()
            val grams = ngrams(text, 3)

            for (key in entries.keys()) {
                val entry = entries.getJSONObject(key)
                if (!entry.optBoolean("enabled", true)) continue

                val primary = readStringsFlexible(entry, "keys") + readStringsFlexible(entry, "key")
                val secondary = readStringsFlexible(entry, "secondary_keys") + readStringsFlexible(entry, "secondary_key")
                val kws = (primary + secondary).map { it.trim() }.filter { it.isNotEmpty() }

                if (kws.any { kw -> norm(kw).let { it.isNotBlank() && it in grams } }) {
                    matches += key
                }
            }
            return matches
        }

        private fun readStringsFlexible(obj: JSONObject, field: String): List<String> {
            if (!obj.has(field)) return emptyList()
            return when (val v = obj.get(field)) {
                is JSONArray -> buildList {
                    for (i in 0 until v.length()) {
                        val s = v.optString(i).trim()
                        if (s.isNotEmpty()) add(s)
                    }
                }
                is String -> v.trim().let { if (it.isNotEmpty()) listOf(it) else emptyList() }
                else -> emptyList()
            }
        }

        private fun updateActivatedLoreFrom(ctx: Context?, text: String) {
            ensureLoreLoaded(ctx)
            val keys = matchLoreKeysInText(text)
            if (keys.isNotEmpty()) activatedLoreKeys.addAll(keys)
        }

        private fun buildActivatedLoreBlock(): List<String> {
            val lore = cachedLoreJson ?: return emptyList()
            val entries = cachedEntries ?: return emptyList()
            val maxLoreTokens = lore.optInt("token_budget", 1000)

            val sortedKeys = activatedLoreKeys.sortedByDescending { k ->
                entries.getJSONObject(k).optInt("priority", 0)
            }

            val result = mutableListOf<String>()
            var tokenCount = 0
            for (k in sortedKeys) {
                val e = entries.getJSONObject(k)
                val content = e.optString("content", "").trim()
                if (content.isEmpty()) continue
                val entryTokens = estimateTokens(content)
                if (tokenCount + entryTokens <= maxLoreTokens) {
                    val name = e.optString("name", "Lore")
                    result += "$name: $content"
                    tokenCount += entryTokens
                } else break
            }
            return result
        }

        private fun recomputeActivatedLoreFromHistory(ctx: Context?, extraText: String? = null) {
            ensureLoreLoaded(ctx)
            activatedLoreKeys.clear()
            for (i in 1 until historyAI.size) {
                val content = historyAI[i]["content"] ?: continue
                updateActivatedLoreFrom(ctx, content)
            }
            if (!extraText.isNullOrBlank()) updateActivatedLoreFrom(ctx, extraText)
        }

        fun rebuildSystemFromHistory(ctx: Context) {
            recomputeActivatedLoreFromHistory(ctx, null)
            val matchedLore = buildActivatedLoreBlock()
            val loreSection = if (matchedLore.isNotEmpty()) "\n\nRelevant Lore:\n${matchedLore.joinToString("\n")}" else ""
            val systemContent = setupString + loreSection
            if (historyAI.isEmpty()) {
                historyAI = mutableListOf(mapOf("role" to "system", "content" to systemContent))
            } else {
                historyAI[0] = mapOf("role" to "system", "content" to systemContent)
            }
        }

        // ===================== Misc utils =====================
        fun estimateTokens(text: String): Int = text.length / 4
    } // <-- end of companion object
} // <-- end of class AIBackend

