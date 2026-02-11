package com.spkdev.echomate

import java.io.Serializable

data class JsonItem(
    val imageUrl: String,
    val name: String,
    val description: String,
    val creatorNotes: String,
    val tags: List<String>,
    val jsonUri: String
) : Serializable
