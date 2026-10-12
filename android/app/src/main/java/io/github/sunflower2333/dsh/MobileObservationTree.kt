package io.github.sunflower2333.dsh

/** Bounded traversal shared by the Android observer and its host-side regression tests. */
internal object MobileObservationTree {
    private const val BOUND_LIMIT = 100_000

    fun acceptsBounds(left: Int, top: Int, right: Int, bottom: Int): Boolean =
        left in -BOUND_LIMIT..BOUND_LIMIT && top in -BOUND_LIMIT..BOUND_LIMIT &&
            right in -BOUND_LIMIT..BOUND_LIMIT && bottom in -BOUND_LIMIT..BOUND_LIMIT &&
            right >= left && bottom >= top

    /**
     * The caller owns root; this traversal releases every child it acquires.
     * Returning false from visit omits only that node, never its descendants.
     * Every child lookup, including a null result, consumes the scan budget.
     */
    fun <T : Any> scan(
        root: T,
        maxVisitedNodes: Int,
        maxDepth: Int,
        childCount: (T) -> Int,
        childAt: (T, Int) -> T?,
        release: (T) -> Unit,
        visit: (T, List<Int>) -> Boolean
    ): Boolean {
        require(maxVisitedNodes > 0 && maxDepth >= 0)
        var visited = 1
        var truncated = false
        fun walk(node: T, path: List<Int>, depth: Int) {
            if (!visit(node, path)) truncated = true
            val count = childCount(node)
            if (count > 0 && depth >= maxDepth) {
                truncated = true
                return
            }
            for (index in 0 until count) {
                if (visited >= maxVisitedNodes) {
                    truncated = true
                    break
                }
                visited++
                val child = childAt(node, index) ?: continue
                try { walk(child, path + index, depth + 1) } finally { release(child) }
            }
        }
        walk(root, emptyList(), 0)
        return truncated
    }
}

/** Deterministic, bounded XML rendering over a releasable accessibility tree. */
internal object MobileUiXmlSerializer {
    data class Limits(val maxNodes: Int = 1000, val maxDepth: Int = 40, val maxBytes: Int = 256 * 1024)
    interface Node {
        val className: String?
        val viewId: String?
        val packageName: String?
        val bounds: String
        val enabled: Boolean
        val visible: Boolean
        val clickable: Boolean
        val editable: Boolean
        val scrollable: Boolean
        val password: Boolean
        val text: String?
        val description: String?
        fun childCount(): Int
        fun childAt(index: Int): Node?
        fun release()
    }
    data class Result(val xml: String, val truncated: Boolean, val nodes: Int)

    fun serialize(root: Node, limits: Limits = Limits()): Result {
        require(limits.maxNodes > 0 && limits.maxDepth >= 0 && limits.maxBytes >= 7)
        val out = StringBuilder(minOf(limits.maxBytes, 4096)); var count = 0; var truncated = false
        fun esc(value: String): String = buildString {
            value.codePoints().forEach { cp -> when (cp) {
                38 -> append("&amp;"); 60 -> append("&lt;"); 62 -> append("&gt;")
                34 -> append("&quot;"); 39 -> append("&apos;")
                else -> if (cp in 0x20..0xD7FF || cp in 0xE000..0xFFFD || cp in 0x10000..0x10FFFF || cp == 9 || cp == 10 || cp == 13) appendCodePoint(cp)
            } }
        }
        fun attr(name: String, value: String?) { if (value != null) out.append(' ').append(name).append("=\"").append(esc(value.take(512))).append("\"") }
        fun walk(node: Node, depth: Int) {
            if (count >= limits.maxNodes || depth > limits.maxDepth) { truncated = true; return }
            val start = out.length; count++
            out.append("<node"); attr("class", node.className); attr("resource-id", node.viewId); attr("package", node.packageName)
            attr("bounds", node.bounds); attr("enabled", node.enabled.toString()); attr("visible", node.visible.toString())
            attr("clickable", node.clickable.toString()); attr("editable", node.editable.toString()); attr("scrollable", node.scrollable.toString()); attr("password", node.password.toString())
            val text = if (node.password) "[REDACTED]" else node.text; val desc = if (node.password) "[REDACTED]" else node.description
            attr("text", text); attr("content-desc", desc)
            out.append('>')
            val childCount = node.childCount()
            for (index in 0 until childCount) {
                if (out.length > limits.maxBytes * 2) { truncated = true; break }
                val child = node.childAt(index) ?: continue
                try { walk(child, depth + 1) } finally { child.release() }
                if (truncated) break
            }
            out.append("</node>")
            if (out.toString().toByteArray(Charsets.UTF_8).size > limits.maxBytes) {
                out.setLength(start); truncated = true
                if ("<node/>".toByteArray(Charsets.UTF_8).size <= limits.maxBytes) out.append("<node/>")
            }
        }
        try { walk(root, 0) } finally { root.release() }
        return Result(out.toString(), truncated, count)
    }
}
