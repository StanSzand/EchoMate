package com.spkdev.echomate

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Filter
import android.widget.Filterable
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.squareup.picasso.Picasso

class JsonAdapter(
    private val onClick: (JsonItem) -> Unit
) : RecyclerView.Adapter<JsonAdapter.ViewHolder>(), Filterable {

    var items: MutableList<JsonItem> = mutableListOf() // Full list of items
    var filteredItems: MutableList<JsonItem> = mutableListOf() // Filtered items displayed

    fun updateItems(newItems: List<JsonItem>) {
        items = newItems.toMutableList()
        filteredItems = newItems.toMutableList()
        notifyDataSetChanged()
    }

    fun addItems(newItems: List<JsonItem>) {
        val startIndex = filteredItems.size
        filteredItems.addAll(newItems)
        notifyItemRangeInserted(startIndex, newItems.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_json, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = filteredItems[position]
        Picasso.get().load(item.imageUrl).into(holder.imageView) // Load image using Picasso
        holder.textViewName.text = item.name
        holder.jsonCreatorNotes.text = item.creatorNotes
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = filteredItems.size



    override fun getFilter(): Filter {
        return object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val query = constraint?.toString()?.lowercase() ?: ""
                val result = if (query.isEmpty()) {
                    items
                } else {
                    items.filter {
                        // Check if the name, creatorNotes, or any tag matches the query
                        it.name.lowercase().contains(query) ||
                                it.creatorNotes.lowercase().contains(query) ||
                                it.tags.any { tag -> tag.lowercase().contains(query) }
                    }
                }
                return FilterResults().apply { values = result }
            }

            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                results?.values?.let {
                    filteredItems = (it as List<JsonItem>).toMutableList()
                    notifyDataSetChanged()
                }
            }
        }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.imageView)
        val textViewName: TextView = view.findViewById(R.id.textViewName)
        val jsonCreatorNotes: TextView = view.findViewById(R.id.creatorNotes)
    }




}
