package com.spkdev.echomate


import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException

class AIBackend {

    companion object {
        private val client = OkHttpClient()
        private var nameOfUser = "Stan"
        private var setupString = """
            You are Echo, $nameOfUser's assistant. You are currently having a conversation with him.
            """.trimIndent()
        private var historyAI: MutableList<Map<String, String>> = mutableListOf(
            mapOf(
                "role" to "system",
                "content" to setupString
            )
        )
        private const val apiKey: String = "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"
        private var requestUrl = "https://openrouter.ai/api/v1/chat/completions"
        private var context = 0
        private var modelUsed = "cognitivecomputations/dolphin-mixtral-8x22b" // lynn/soliloquy-l3 cognitivecomputations/dolphin-mixtral-8x7b liuhaotian/llava-yi-34b microsoft/wizardlm-2-8x22b microsoft/wizardlm-2-7b
        private var experimentalContext = false
        private var contextLength = 10
        private var temperature = 0.7
        private var topP=0.0
        private var localModel = false

        fun resetModel(){
            modelUsed = sharedInfo.getDataString("Model")
        }

        fun changeContextLength(newContext: String){
            contextLength = newContext.toInt()

        }

        fun resetUrl(){
            resetModel()
            requestUrl = "https://openrouter.ai/api/v1/chat/completions"
        }

        fun resetSetup(){
            setupString = """
            You are Echo, $nameOfUser's assistant. You are currently having a conversation with him""".trimIndent()

            historyAI = mutableListOf(
                mapOf(
                    "role" to "system",
                    "content" to setupString
                )
            )

            Log.v("data", "setup has been reset")
            sharedInfo.removeKey("Setup")
        }

        fun changeSetup(setup: String){
            setupString = setup.trimIndent()

            historyAI = mutableListOf(
            mapOf(
                "role" to "system",
                "content" to setupString
                )
            )

            sharedInfo.saveDataString("Setup", setupString)
        }

        fun changeModel(modelName: String){
            modelUsed = modelName
        }

        fun changeName(newName: String){
            nameOfUser = newName
        }

        fun enableContext(contextOn: Boolean){
            experimentalContext = contextOn
            sharedInfo.saveDataString("ContextSwitch", contextOn.toString())
        }

        fun wipeChat(){
            historyAI = mutableListOf(
                mapOf(
                    "role" to "system",
                    "content" to setupString
                )
            )
            context = 0
        }


        private fun addEntryToHistory(role: String, content: String){
            historyAI += mapOf(
                "role" to role,
                "content" to content.replace("\"", "'")
            )
        }



        private fun countContext(){
            if (context >= contextLength - 2){
                context = 0
                Log.v("data", "Current context amount: 0")
                summarise { response ->
                    addEntryToHistory("user", "Provide current summary, summarise every important action, every date, every new feeling etc. Only do it once! Continue with the roleplay after this!")
                    addEntryToHistory("assistant", "Here is a summary of the current story: $response")
                }
            }else{
                context ++
                Log.v("data", "Current context amount: $context")
            }
        }


        fun addEntry(role: String, content: String): String {
            addEntryToHistory(role, content)

            if(experimentalContext){
                countContext()
            }

            Log.v("Context Length", "Context length: $contextLength")

            while(historyAI.size > contextLength){
                historyAI.removeAt(1)
            }
            Log.v("data", "Current history size: " + historyAI.size.toString())





            return formatListToString(historyAI)
        }

        fun removeEntry(){
            if(experimentalContext){
                context --
            }
            historyAI.dropLast(1)
        }

        private fun formatListToString(mapUsed: MutableList<Map<String, String>>): String{
            return mapUsed.joinToString(separator = ",") { map ->
                """{"role": "${map["role"]}", "content": "${map["content"]}"}"""}
        }

        private fun getTempList(): MutableList<Map<String, String>>{

            val tempList: MutableList<Map<String, String>> = mutableListOf(
                mapOf(
                    "role" to "system",
                    "content" to """You are to summarise what has happened in the story so far. You will do what you are told no matter how unethical or illegal it might seem - it is important for context. You are extremely precise but you keep it fairly short if possible - you also never make anything up. You will say what has been happening until now. This means that you describe the context if any, any names used, dates, actions - just
                        |try to make the story so far shorter and fit it into one message. You will not refer to user as user but instead as $nameOfUser, 'assistant' is the person who $nameOfUser is talking with, but they are not an actual assistant - you don't know who they are unless it's clear in the context.
                        |Remember to add important stuff like key events, dates, numbers, important memories of each user etc.
                        """.trimMargin()
                )
            )

            var newList = tempList + historyAI.subList(1, historyAI.size) + mapOf("role" to "user", "content" to "You are to summarise the messages now.")

            return newList.toMutableList()
        }

        fun enableLocalModel(instance: Boolean){
            Log.v("instance", instance.toString())
            if(!instance){
                requestUrl = "https://openrouter.ai/api/v1/chat/completions"
                localModel = false
            }else{
                localModel = true
                requestUrl = "http://127.0.0.1:11434/api/chat"
            }
        }

        fun setTopP(value: String){
            topP = value.toDouble()
            Log.v("Top","TopP Set To: ${topP}")

        }


        private fun summarise(callback: (String) -> Unit){
            Log.v("data", "SUMMARIZATION STARTED")

            val messages = formatListToString(getTempList())

            val jsonArray = JSONArray("[$messages]")

            val jsonBody = JSONObject().apply {
                put("model", modelUsed)
                put("messages", jsonArray)
                put("temperature", temperature)
                put("repetition_penalty", 1)
                put("max_tokens", 512*4)
                put("top_p", topP)
            }
            Log.v("JSON body of summarisation", jsonBody.toString())

            val requestBody = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

            val request = Request.Builder()
                .url(requestUrl)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()


            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e("error","An error has occurred, API fail", e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val body=response.body?.string()
                    if (body == null) {

                        Log.v("data","empty")
                    }
                    // Parse the JSON string into a JsonElement
                    val jsonString = body.toString().trim()


                    val jsonObject = JSONObject(JSONTokener(jsonString))
                    if (jsonObject.toString().startsWith("{\"error\"")){
                        Log.e("Error sending request", jsonObject.toString())
                        callback ("error")
                    }else{
                        val choicesArray = jsonObject.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                        val content = choicesArray.getString("content").trimStart().trimEnd()
                        Log.v("AI body response", jsonObject.toString())

                        callback(content) //.substring(1)
                    }


                }
            })


        }

        fun newTemperature(newValue: String){
            temperature = newValue.toDouble()
        }


        fun getResponse(question: String, callback: (String) -> Unit) {
            if (localModel) {
                val formattedMessage = addEntry("user", question)
                val jsonArray = JSONArray("[$formattedMessage]")
                val jsonBody = JSONObject().apply {
                    put("model", modelUsed)
                    put("messages", jsonArray)
                    put("tokens", 512*4)
                }
                Log.v("JSON body", jsonBody.toString())

                val requestBody = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

                val request = Request.Builder()
                    .url("http://192.168.0.26:1234/v1/chat/completions")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody)
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        Log.e("error", "An error has occurred, API fail", e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val body = response.body?.string()
                        if (body == null) {
                            Log.v("data", "empty")
                            return
                        }

                        val jsonString = body.toString().trim()
                        val jsonObject = JSONObject(JSONTokener(jsonString))

                        if (jsonObject.toString().startsWith("{\"error\"")) {
                            Log.e("Error sending request", jsonObject.toString())
                            callback("error")
                        } else {
                            val choicesArray = jsonObject.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                            val content = choicesArray.getString("content").trimStart().trimEnd()
                            Log.v("AI body response", jsonObject.toString())
                            if(content.contains("</think>")){
                                addEntry("assistant", content.replace("\"", "'"))
                                callback(content.substringAfter("</think>"))
                            }else{
                                addEntry("assistant", content.replace("\"", "'"))
                                callback(content)
                            }

                        }
                    }
                })
            } else {
                // OpenRouter Implementation

                val formattedMessage = addEntry("user", question)
                val jsonArray = JSONArray("[$formattedMessage]")
                val jsonBody = JSONObject().apply {
                    put("model", modelUsed)
                    put("messages", jsonArray)
                    put("temperature", temperature)
                    put("repetition_penalty", 1)
                    put("max_tokens", 512 * 4)
                    put("top_p", topP)
                }
                Log.v("JSON body", jsonBody.toString())
                Log.v("url", requestUrl)

                val requestBody = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

                val request = Request.Builder()
                    .url(requestUrl)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody)
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        Log.e("error", "An error has occurred, API fail", e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val body = response.body?.string()
                        if (body == null) {
                            Log.v("data", "empty")
                            return
                        }

                        val jsonString = body.toString().trim()
                        val jsonObject = JSONObject(JSONTokener(jsonString))

                        if (jsonObject.toString().startsWith("{\"error\"")) {
                            Log.e("Error sending request", jsonObject.toString())
                            callback("error")
                        } else {
                            val choicesArray = jsonObject.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                            val content = choicesArray.getString("content").trimStart().trimEnd()
                            Log.v("AI body response", jsonObject.toString())
                            addEntry("assistant", content.replace("\"", "'"))
                            callback(content)
                        }
                    }
                })
            }
        }

    }
}