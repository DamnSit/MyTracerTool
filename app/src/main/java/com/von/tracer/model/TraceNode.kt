// app/src/main/java/com/von/tracer/model/TraceNode.kt

package com.von.tracer.model

import java.util.UUID

data class TraceNode(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val type: NodeType,
    val label: String,
    val offset: String,
    val className: String,
    val methodName: String,
    val extraInfo: String = "",
    val rawJson: String = "",
    val depth: Int = 0,
    val children: MutableList<TraceNode> = mutableListOf(),
    var isExpanded: Boolean = false
) {
    // Format timestamp untuk display
    val timeFormatted: String
        get() {
            val ms = timestamp % 1000
            val s = (timestamp / 1000) % 60
            val m = (timestamp / 60000) % 60
            val h = (timestamp / 3600000) % 24
            return "%02d:%02d:%02d.%03d".format(h, m, s, ms)
        }

    // Short class name untuk display
    val shortClassName: String
        get() = className.substringAfterLast(".")

    val hasChildren: Boolean
        get() = children.isNotEmpty()
}