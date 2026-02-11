package com.spkdev.echomate

import android.util.Log
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

    var items: MutableList<JsonItem> = mutableListOf()         // full dataset
    var filteredItems: MutableList<JsonItem> = mutableListOf() // what’s currently shown

    fun updateItems(newItems: List<JsonItem>) {
        items = newItems.toMutableList()
        filteredItems = newItems.toMutableList()
        notifyDataSetChanged()
    }

    // NEW: set the full dataset without changing what's shown yet
    fun setAllItems(all: List<JsonItem>) {
        items = all.toMutableList()
        // do NOT touch filteredItems here
        notifyDataSetChanged()
    }

    // NEW: append a page to what's visible
    fun showNextPage(nextPage: List<JsonItem>) {
        val start = filteredItems.size
        filteredItems.addAll(nextPage)
        notifyItemRangeInserted(start, nextPage.size)
    }

    fun addItems(newOnes: List<JsonItem>) {
        val start = items.size
        items.addAll(newOnes)
        notifyItemRangeInserted(start, newOnes.size)
    }


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_json, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = filteredItems[position]

        if (item.imageUrl.isNotEmpty()) {
            Picasso.get().load(item.imageUrl).into(holder.imageView)
        } else {
            holder.imageView.setImageResource(R.drawable.placeholder)
        }
        holder.textViewName.text = item.name
        holder.jsonCreatorNotes.text = item.creatorNotes

        // Make absolutely sure the root is clickable and forwards the click
        holder.itemView.isClickable = true
        holder.itemView.setOnClickListener {
            Log.d("JsonAdapter", "Clicked: ${item.name}")
            onClick(item)
        }
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
