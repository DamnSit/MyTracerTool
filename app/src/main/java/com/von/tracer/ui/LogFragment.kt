// app/src/main/java/com/von/tracer/ui/LogFragment.kt

package com.von.tracer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.von.tracer.R
import com.von.tracer.model.TraceNode

class LogFragment : Fragment() {

    companion object {
        fun newInstance() = LogFragment()
    }

    private lateinit var rvTree: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var tvNodeCount: TextView
    private lateinit var tvServerStatus: TextView

    private val adapter = TraceTreeAdapter()

    // ──────────────────────────────────────────────
    // LIFECYCLE
    // ──────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_log, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvTree = view.findViewById(R.id.rvTraceTree)
        tvEmpty = view.findViewById(R.id.tvEmpty)
        tvNodeCount = view.findViewById(R.id.tvNodeCount)
        tvServerStatus = view.findViewById(R.id.tvServerStatus)

        setupRecyclerView()
        setupAdapter()
        showEmpty(true)
    }

    // ──────────────────────────────────────────────
    // SETUP
    // ──────────────────────────────────────────────

    private fun setupRecyclerView() {
        val layoutManager = LinearLayoutManager(requireContext()).apply {
            reverseLayout = false
            stackFromEnd = false
        }
        rvTree.layoutManager = layoutManager
        rvTree.adapter = adapter

        // Smooth scroll ke top setiap ada node baru
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                if (positionStart == 0) {
                    rvTree.scrollToPosition(0)
                }
            }
        })
    }

    private fun setupAdapter() {
        adapter.onNodeLongClick = { node ->
            showNodeOptions(node)
        }
    }

    // ──────────────────────────────────────────────
    // PUBLIC API — dipanggil dari MainActivity
    // ──────────────────────────────────────────────

    fun addTraceNode(node: TraceNode) {
        adapter.addNode(node)
        showEmpty(false)
        updateNodeCount()
    }

    fun clearLog() {
        adapter.clearAll()
        showEmpty(true)
        updateNodeCount()
    }

    fun setServerStatus(running: Boolean) {
        tvServerStatus.text = if (running) "● LIVE" else "○ OFFLINE"
        tvServerStatus.setTextColor(
            if (running) 0xFF4CAF50.toInt() else 0xFF888888.toInt()
        )
    }

    fun exportLog(): String {
        val sb = StringBuilder()
        sb.appendLine("=== VON Tracer Export ===")
        sb.appendLine("Total events: ${adapter.getRootNodes().size}")
        sb.appendLine()
        adapter.getRootNodes().forEach { node ->
            appendNodeToExport(sb, node, 0)
        }
        return sb.toString()
    }

    // ──────────────────────────────────────────────
    // NODE OPTIONS (long press)
    // ──────────────────────────────────────────────

    private fun showNodeOptions(node: TraceNode) {
        val options = arrayOf(
            "Copy Offset",
            "Copy Class Name",
            "Copy Raw JSON",
            "Copy Full Stack"
        )

        android.app.AlertDialog.Builder(requireContext())
            .setTitle(node.label)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> copyToClipboard("Offset", node.offset)
                    1 -> copyToClipboard("Class", node.className)
                    2 -> copyToClipboard("JSON", node.rawJson)
                    3 -> copyToClipboard("Stack", buildFullStack(node))
                }
            }
            .show()
    }

    private fun buildFullStack(node: TraceNode): String {
        val sb = StringBuilder()
        sb.appendLine("${node.type} | ${node.label}")
        sb.appendLine("Offset: ${node.offset}")
        sb.appendLine("Class: ${node.className}")
        sb.appendLine()
        sb.appendLine("Call Stack:")
        node.children.forEachIndexed { i, child ->
            sb.appendLine("  #$i ${child.className}.${child.methodName}() [${child.offset}]")
        }
        return sb.toString()
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = requireContext()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(requireContext(), "$label copied", Toast.LENGTH_SHORT).show()
    }

    // ──────────────────────────────────────────────
    // EXPORT HELPER
    // ──────────────────────────────────────────────

    private fun appendNodeToExport(sb: StringBuilder, node: TraceNode, depth: Int) {
        val indent = "  ".repeat(depth)
        sb.appendLine("${indent}[${node.type}] ${node.label}")
        sb.appendLine("${indent}  offset : ${node.offset}")
        sb.appendLine("${indent}  class  : ${node.className}")
        sb.appendLine("${indent}  time   : ${node.timeFormatted}")
        if (node.extraInfo.isNotEmpty()) {
            sb.appendLine("${indent}  extra  : ${node.extraInfo}")
        }
        node.children.forEach { child ->
            appendNodeToExport(sb, child, depth + 1)
        }
        if (depth == 0) sb.appendLine()
    }

    // ──────────────────────────────────────────────
    // UI HELPERS
    // ──────────────────────────────────────────────

    private fun showEmpty(empty: Boolean) {
        tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        rvTree.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun updateNodeCount() {
        val count = adapter.getRootNodes().size
        tvNodeCount.text = "$count events"
    }
}