package com.spkdev.echomate

import java.io.Serializable

data class JsonItem(
    val imageUrl: String,
    val name: String,
    val description: String,
    val creatorNotes: String,
    val jsonObject: String,
    val tags: List<String> // New array for tags
) : Serializable
