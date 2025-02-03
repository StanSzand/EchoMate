package com.spkdev.echomate

import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

data class ChatMessage(
    val message: String,
    val isSentByCurrentUser: Boolean
)

class ChatAdapter(private val messages: List<ChatMessage>) :
    RecyclerView.Adapter<ChatAdapter.MessageViewHolder>() {

    inner class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val leftChatLayout: LinearLayout = itemView.findViewById(R.id.left_chat_layout)
        val rightChatLayout: LinearLayout = itemView.findViewById(R.id.right_chat_layout)
        val leftChatTextView: TextView = itemView.findViewById(R.id.left_chat_textview)
        val rightChatTextView: TextView = itemView.findViewById(R.id.right_chat_textview)
    }

    // Add the formatText function here
    fun formatText(input: String, context: Context): Spannable {
        // Replace literal "\n" and "\r" with an empty string
        val sanitizedInput = input.replace("\\n", "").replace("\\r", "")

        // Copy of the sanitized input to calculate the correct indices after removing *
        val originalInput = sanitizedInput
        val cleanedInput = sanitizedInput.replace("*", "")
        val spannable = SpannableString(cleanedInput)

        val regex = "\\*(.*?)\\*".toRegex()
        val matches = regex.findAll(originalInput)

        var offset = 0

        matches.forEach { match ->
            val originalStart = match.range.first
            val originalEnd = match.range.last

            // Calculate the adjusted start and end indices
            val start = originalStart - offset
            val end = originalEnd - 1 - offset

            // Ensure the indices are within bounds
            if (start >= 0 && end <= spannable.length) {
                // Apply italic style
                spannable.setSpan(
                    StyleSpan(Typeface.ITALIC),
                    start,
                    end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                // Apply gray color
                val grayColor = ContextCompat.getColor(context, android.R.color.black)
                spannable.setSpan(
                    ForegroundColorSpan(grayColor),
                    start,
                    end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            // Adjust offset for the removed * characters
            offset += 2
        }

        return spannable
    }





    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.chat_message_recycler, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        if (message.isSentByCurrentUser) {
            holder.rightChatLayout.visibility = View.VISIBLE
            holder.leftChatLayout.visibility = View.GONE
            holder.rightChatTextView.text = formatText(message.message, holder.itemView.context)
        } else {
            holder.leftChatLayout.visibility = View.VISIBLE
            holder.rightChatLayout.visibility = View.GONE
            // Use formatText to apply the styles when setting the text
            holder.leftChatTextView.text = formatText(message.message, holder.itemView.context)
        }
    }

    override fun getItemCount() = messages.size
}
