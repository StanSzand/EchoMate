package com.spkdev.echomate

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.squareup.picasso.Picasso

data class ChatMessage(
    val message: String,
    val isSentByCurrentUser: Boolean,
    val imageUris: List<String> = emptyList()
)

private val formattedCache = object : android.util.LruCache<String, CharSequence>(200) {}
private fun formatTextCached(id: String, raw: String, formatter: (String) -> CharSequence): CharSequence {
    formattedCache.get(id)?.let { return it }
    val out = formatter(raw)
    formattedCache.put(id, out)
    return out
}

class ChatAdapter(
    private val messages: MutableList<ChatMessage>,
    private val onEditMessage: (position: Int, newText: String) -> Unit
) : RecyclerView.Adapter<ChatAdapter.MessageViewHolder>() {

    inner class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val leftChatLayout: LinearLayout = itemView.findViewById(R.id.left_chat_layout)
        val rightChatLayout: LinearLayout = itemView.findViewById(R.id.right_chat_layout)
        val leftChatTextView: TextView = itemView.findViewById(R.id.left_chat_textview)
        val rightChatTextView: TextView = itemView.findViewById(R.id.right_chat_textview)
        val leftImagesScroll: HorizontalScrollView = itemView.findViewById(R.id.left_chat_images_scroll)
        val rightImagesScroll: HorizontalScrollView = itemView.findViewById(R.id.right_chat_images_scroll)
        val leftImagesContainer: LinearLayout = itemView.findViewById(R.id.left_chat_images_container)
        val rightImagesContainer: LinearLayout = itemView.findViewById(R.id.right_chat_images_container)
    }

    private fun SpannableStringBuilder.safeSetSpan(
        what: Any,
        start: Int,
        end: Int,
        flags: Int = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
    ) {
        if (start < end) setSpan(what, start, end, flags)
    }

    private fun showEditDialog(ctx: Context, adapterPos: Int) {
        val original = messages[adapterPos]
        val input = android.widget.EditText(ctx).apply {
            setText(original.message)
            setSelection(text.length)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
            maxLines = 6
        }

        AlertDialog.Builder(ctx)
            .setTitle("Edit message")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newText = input.text.toString()
                if (newText != original.message) {
                    onEditMessage(adapterPos, newText)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun bindImagePreviews(scrollView: HorizontalScrollView, container: LinearLayout, imageUris: List<String>) {
        container.removeAllViews()
        if (imageUris.isEmpty()) {
            scrollView.visibility = View.GONE
            return
        }

        val size = (92 * container.resources.displayMetrics.density).toInt()
        val margin = (6 * container.resources.displayMetrics.density).toInt()

        imageUris.forEachIndexed { index, uri ->
            if (uri.isBlank()) return@forEachIndexed
            val imageView = ImageView(container.context).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    if (index > 0) marginStart = margin
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                adjustViewBounds = true
            }

            Picasso.get()
                .load(uri)
                .resize(size, size)
                .centerCrop()
                .into(imageView)

            imageView.setOnClickListener {
                val fullImage = ImageView(it.context).apply {
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    Picasso.get().load(uri).into(this)
                }
                AlertDialog.Builder(it.context)
                    .setView(fullImage)
                    .setPositiveButton("Close", null)
                    .show()
            }

            container.addView(imageView)
        }

        scrollView.visibility = if (container.childCount > 0) View.VISIBLE else View.GONE
    }

    fun formatText(input: String, context: Context): CharSequence {
        return formatTextCached(id = "fmt:${input.hashCode()}", raw = input) { raw ->
            val sanitizedInput = raw.replace("\r\n", "\n")
            val regex = Regex("""(?<!\\)\*(.+?)\*""")
            val sb = SpannableStringBuilder()
            var lastIndex = 0

            for (match in regex.findAll(sanitizedInput)) {
                val before = sanitizedInput
                    .substring(lastIndex, match.range.first)
                    .replace("""\*""", "*")
                sb.append(before)

                val italicText = match.groupValues[1].replace("""\*""", "*")
                val start = sb.length
                sb.append(italicText)
                val end = sb.length

                val color = ContextCompat.getColor(context, android.R.color.black)
                sb.safeSetSpan(StyleSpan(Typeface.ITALIC), start, end)
                sb.safeSetSpan(ForegroundColorSpan(color), start, end)
                lastIndex = match.range.last + 1
            }

            if (lastIndex < sanitizedInput.length) {
                val tail = sanitizedInput.substring(lastIndex).replace("""\*""", "*")
                sb.append(tail)
            }

            if (sb.isEmpty()) SpannableString(sanitizedInput) else sb
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.chat_message_recycler, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        val styled = formatText(message.message, holder.itemView.context)

        if (message.isSentByCurrentUser) {
            holder.rightChatLayout.visibility = View.VISIBLE
            holder.leftChatLayout.visibility = View.GONE
            holder.rightChatTextView.text = styled
            holder.rightChatTextView.visibility = if (message.message.isBlank()) View.GONE else View.VISIBLE
            bindImagePreviews(holder.rightImagesScroll, holder.rightImagesContainer, message.imageUris)

            holder.rightChatTextView.setOnLongClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) showEditDialog(it.context, pos)
                true
            }
        } else {
            holder.leftChatLayout.visibility = View.VISIBLE
            holder.rightChatLayout.visibility = View.GONE
            holder.leftChatTextView.text = styled
            holder.leftChatTextView.visibility = if (message.message.isBlank()) View.GONE else View.VISIBLE
            bindImagePreviews(holder.leftImagesScroll, holder.leftImagesContainer, message.imageUris)

            holder.leftChatTextView.setOnLongClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) showEditDialog(it.context, pos)
                true
            }
        }
    }

    override fun getItemCount() = messages.size
}
