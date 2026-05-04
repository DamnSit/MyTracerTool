// app/src/main/java/com/von/tracer/server/TraceParser.kt

package com.von.tracer.server

import com.von.tracer.model.NodeType
import com.von.tracer.model.TraceNode
import org.json.JSONObject
import java.util.UUID

class TraceParser {

    // ──────────────────────────────────────────────
    // MAIN PARSE
    // ──────────────────────────────────────────────

    /**
     * Parse JSON string dari agent.js → TraceNode dengan children.
     *
     * Format JSON dari agent:
     * {
     *   "type": "CLICK" | "TOUCH" | "METHOD" | "NATIVE",
     *   "viewClass": "com.example.LoginButton",
     *   "viewId": 12345,
     *   "text": "Login",
     *   "offset": "0x1A2B3C",
     *   "method": "onClick",
     *   "x": 320.5,        ← untuk TOUCH
     *   "y": 580.2,        ← untuk TOUCH
     *   "stack": [
     *     { "class": "com.example.MainActivity", "method": "onClick", "line": 42 },
     *     ...
     *   ]
     * }
     */
    fun parse(json: String): TraceNode? {
        return try {
            val obj = JSONObject(json)
            parseObject(obj)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseObject(obj: JSONObject): TraceNode {
        val type = resolveNodeType(obj.optString("type", "METHOD"))
        val stack = obj.optJSONArray("stack")

        // Build children dari call stack
        val children = mutableListOf<TraceNode>()
        if (stack != null) {
            for (i in 0 until stack.length()) {
                val frame = stack.getJSONObject(i)
                val frameClass = frame.optString("class", "?")
                val frameMethod = frame.optString("method", "?")
                val frameLine = frame.optInt("line", -1)

                // Skip Android framework frames — fokus ke app code
                if (shouldSkipFrame(frameClass)) continue

                children.add(
                    TraceNode(
                        id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        type = NodeType.METHOD_CALL,
                        label = "$frameMethod()",
                        offset = if (frameLine > 0) "line:$frameLine" else "?",
                        className = frameClass,
                        methodName = frameMethod,
                        rawJson = frame.toString(),
                        depth = i
                    )
                )
            }
        }

        return TraceNode(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            type = type,
            label = buildLabel(type, obj),
            offset = obj.optString("offset", "0x?"),
            className = obj.optString("viewClass", obj.optString("class", "Unknown")),
            methodName = obj.optString("method", ""),
            extraInfo = buildExtraInfo(type, obj),
            rawJson = obj.toString(),
            children = children,
            depth = 0
        )
    }

    // ──────────────────────────────────────────────
    // LABEL BUILDER
    // ──────────────────────────────────────────────

    private fun buildLabel(type: NodeType, obj: JSONObject): String {
        return when (type) {
            NodeType.CLICK_LISTENER -> {
                val text = obj.optString("text", "")
                val cls = obj.optString("viewClass", "View").substringAfterLast(".")
                if (text.isNotEmpty()) "Click [$text] → $cls"
                else "Click → $cls"
            }
            NodeType.TOUCH_EVENT -> {
                val x = obj.optDouble("x", 0.0)
                val y = obj.optDouble("y", 0.0)
                "Touch (${x.toInt()}, ${y.toInt()})"
            }
            NodeType.METHOD_CALL -> {
                val cls = obj.optString("class", "?").substringAfterLast(".")
                val method = obj.optString("method", "?")
                "$cls.$method()"
            }
            NodeType.NATIVE_CALL -> {
                val lib = obj.optString("lib", "?")
                val sym = obj.optString("symbol", "?")
                "Native: $lib → $sym"
            }
            NodeType.IAP_TRIGGER -> {
                val sku = obj.optString("sku", "?")
                "IAP: $sku"
            }
        }
    }

    private fun buildExtraInfo(type: NodeType, obj: JSONObject): String {
        return when (type) {
            NodeType.CLICK_LISTENER -> {
                val id = obj.optInt("viewId", -1)
                if (id > 0) "viewId=0x${id.toString(16)}" else ""
            }
            NodeType.TOUCH_EVENT -> {
                val action = obj.optInt("action", 0)
                val actionStr = when (action) {
                    0 -> "ACTION_DOWN"
                    1 -> "ACTION_UP"
                    2 -> "ACTION_MOVE"
                    else -> "ACTION_$action"
                }
                actionStr
            }
            NodeType.NATIVE_CALL -> {
                "offset=${obj.optString("offset", "?")}"
            }
            else -> ""
        }
    }

    // ──────────────────────────────────────────────
    // HELPERS
    // ──────────────────────────────────────────────

    private fun resolveNodeType(typeStr: String): NodeType {
        return when (typeStr.uppercase()) {
            "CLICK" -> NodeType.CLICK_LISTENER
            "TOUCH" -> NodeType.TOUCH_EVENT
            "METHOD" -> NodeType.METHOD_CALL
            "NATIVE" -> NodeType.NATIVE_CALL
            "IAP" -> NodeType.IAP_TRIGGER
            else -> NodeType.METHOD_CALL
        }
    }

    private fun shouldSkipFrame(className: String): Boolean {
        // Filter out noise dari Android framework
        val skipPrefixes = listOf(
            "android.",
            "androidx.",
            "java.",
            "javax.",
            "kotlin.",
            "kotlinx.",
            "dalvik.",
            "com.android.",
            "sun.",
            "libcore."
        )
        return skipPrefixes.any { className.startsWith(it) }
    }
}