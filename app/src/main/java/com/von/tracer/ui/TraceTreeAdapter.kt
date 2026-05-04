package com.von.tracer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.von.tracer.R
import com.von.tracer.model.NodeType
import com.von.tracer.model.TraceNode

class TraceTreeAdapter : RecyclerView.Adapter<TraceTreeAdapter.ViewHolder>() {

    companion object {
        private const val INDENT_DP = 20 // px per depth level
    }

    // Root nodes (event utama)
    private val rootNodes = mutableListOf<TraceNode>()

    // Flat list untuk RecyclerView — rebuild setiap expand/collapse
    private val flatList = mutableListOf<TraceNode>()

    var onNodeLongClick: ((TraceNode) -> Unit)? = null

    // ──────────────────────────────────────────────
    // PUBLIC API
    // ──────────────────────────────────────────────

    fun addNode(node: TraceNode) {
        rootNodes.add(0, node) // newest first
        rebuildFlatList()
        notifyDataSetChanged()
    }

    fun clearAll() {
        rootNodes.clear()
        flatList.clear()
        notifyDataSetChanged()
    }

    fun getRootNodes(): List<TraceNode> = rootNodes.toList()

    // ──────────────────────────────────────────────
    // FLAT LIST BUILDER
    // ──────────────────────────────────────────────

    private fun rebuildFlatList() {
        flatList.clear()
        rootNodes.forEach { root ->
            flattenNode(root)
        }
    }

    private fun flattenNode(node: TraceNode) {
        flatList.add(node)
        if (node.isExpanded && node.hasChildren) {
            node.children.forEach { child ->
                flattenNode(child)
            }
        }
    }

    // ──────────────────────────────────────────────
    // RECYCLER ADAPTER
    // ──────────────────────────────────────────────

    override fun getItemCount() = flatList.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_trace_node, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val node = flatList[position]
        holder.bind(node)

        // Expand / collapse on click
        holder.itemView.setOnClickListener {
            if (node.hasChildren) {
                node.isExpanded = !node.isExpanded
                rebuildFlatList()
                notifyDataSetChanged()
            }
        }

        // Long click → copy raw JSON / offset
        holder.itemView.setOnLongClickListener {
            onNodeLongClick?.invoke(node)
            true
        }
    }

    // ──────────────────────────────────────────────
    // VIEW HOLDER
    // ──────────────────────────────────────────────

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {

        private val container: LinearLayout = view.findViewById(R.id.containerNode)
        private val ivExpand: ImageView = view.findViewById(R.id.ivExpand)
        private val ivTypeIcon: ImageView = view.findViewById(R.id.ivTypeIcon)
        private val tvLabel: TextView = view.findViewById(R.id.tvLabel)
        private val tvOffset: TextView = view.findViewById(R.id.tvOffset)
        private val tvClass: TextView = view.findViewById(R.id.tvClass)
        private val tvTime: TextView = view.findViewById(R.id.tvTime)
        private val tvExtra: TextView = view.findViewById(R.id.tvExtra)
        private val divider: View = view.findViewById(R.id.divider)

        fun bind(node: TraceNode) {
            val ctx = itemView.context
            val density = ctx.resources.displayMetrics.density

            // Indentasi berdasarkan depth
            val paddingLeft = (node.depth * INDENT_DP * density).toInt()
                .coerceAtLeast((16 * density).toInt())
            container.setPadding(paddingLeft, 0, (8 * density).toInt(), 0)

            // Label utama
            tvLabel.text = node.label
            tvLabel.setTextColor(getNodeColor(node.type))

            // Offset — monospace
            tvOffset.text = "⬡ ${node.offset}"
            tvOffset.visibility = if (node.offset == "0x?") View.GONE else View.VISIBLE

            // Class name
            tvClass.text = node.shortClassName
            tvClass.visibility = if (node.shortClassName.isEmpty()) View.GONE else View.VISIBLE

            // Timestamp
            tvTime.text = node.timeFormatted

            // Extra info (viewId, action type, dll)
            if (node.extraInfo.isNotEmpty()) {
                tvExtra.text = node.extraInfo
                tvExtra.visibility = View.VISIBLE
            } else {
                tvExtra.visibility = View.GONE
            }

            // Type icon
            ivTypeIcon.setImageResource(getNodeIcon(node.type))
            ivTypeIcon.setColorFilter(getNodeColor(node.type))

            // Expand indicator
            if (node.hasChildren) {
                ivExpand.visibility = View.VISIBLE
                val rotation = if (node.isExpanded) 90f else 0f
                ivExpand.rotation = rotation
            } else {
                ivExpand.visibility = View.INVISIBLE
            }

            // Divider hanya untuk root nodes (depth 0)
            divider.visibility = if (node.depth == 0) View.VISIBLE else View.GONE

            // Background highlight untuk root node
            if (node.depth == 0) {
                container.setBackgroundResource(R.drawable.bg_node_root)
            } else {
                container.setBackgroundResource(R.drawable.bg_node_child)
            }
        }

        private fun getNodeColor(type: NodeType): Int {
            return when (type) {
                NodeType.TOUCH_EVENT    -> 0xFF2196F3.toInt() // biru
                NodeType.CLICK_LISTENER -> 0xFF4CAF50.toInt() // hijau
                NodeType.METHOD_CALL    -> 0xFFFF9800.toInt() // orange
                NodeType.NATIVE_CALL    -> 0xFFE91E63.toInt() // pink
                NodeType.IAP_TRIGGER    -> 0xFFF44336.toInt() // merah
            }
        }

        private fun getNodeIcon(type: NodeType): Int {
            return when (type) {
                NodeType.TOUCH_EVENT    -> R.drawable.ic_touch
                NodeType.CLICK_LISTENER -> R.drawable.ic_click
                NodeType.METHOD_CALL    -> R.drawable.ic_method
                NodeType.NATIVE_CALL    -> R.drawable.ic_native
                NodeType.IAP_TRIGGER    -> R.drawable.ic_iap
            }
        }
    }
}