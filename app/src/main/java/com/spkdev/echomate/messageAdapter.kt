package com.spkdev.echomate

import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
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
        val sanitizedInput = input.replace("\\n", "").replace("\\r", "")

        val regex = "\\*(.*?)\\*".toRegex()
        val spannableBuilder = SpannableStringBuilder()

        var lastIndex = 0

        for (match in regex.findAll(sanitizedInput)) {
            // Append text before the match
            spannableBuilder.append(sanitizedInput.substring(lastIndex, match.range.first))

            // This is the actual word inside *...*
            val italicText = match.groupValues[1]

            // Mark start index of where this word will be in final text
            val start = spannableBuilder.length
            spannableBuilder.append(italicText)
            val end = spannableBuilder.length

            // Apply styles
            spannableBuilder.setSpan(
                StyleSpan(Typeface.ITALIC),
                start,
                end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            val grayColor = ContextCompat.getColor(context, android.R.color.black)
            spannableBuilder.setSpan(
                ForegroundColorSpan(grayColor),
                start,
                end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            lastIndex = match.range.last + 1
        }

        // Append the rest of the text
        if (lastIndex < sanitizedInput.length) {
            spannableBuilder.append(sanitizedInput.substring(lastIndex))
        }

        return spannableBuilder
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
