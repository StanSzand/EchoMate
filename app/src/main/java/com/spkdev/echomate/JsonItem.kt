package com.spkdev.echomate

import android.net.Uri
import java.io.Serializable

data class JsonItem(
    val imageUrl: String,
    val name: String,
    val description: String,
    val creatorNotes: String,
    val jsonObject: String,
    val tags: List<String>,
    val fileUri: Uri? = null // New field for local file/lorebook reference
) : Serializable
