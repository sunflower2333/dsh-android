package io.github.sunflower2333.dsh

import org.junit.Assert.*
import org.junit.Test

class MobileUiXmlTest {
    private class N(val name: String, val kids: List<N> = emptyList(), val secret: Boolean = false) : MobileUiXmlSerializer.Node {
        var released = false
        override val className = name; override val viewId = "id&1"; override val packageName = "pkg<1>"
        override val bounds = "0,0,10,20"; override val enabled = true; override val visible = true
        override val clickable = true; override val editable = false; override val scrollable = false
        override val password = secret; override val text = "a<&\""; override val description = "desc"
        override fun childCount() = kids.size; override fun childAt(index: Int) = kids[index]
        override fun release() { released = true }
    }

    @Test fun escapesAndRedacts() {
        val root = N("Root", listOf(N("Password", secret = true)))
        val result = MobileUiXmlSerializer.serialize(root)
        assertTrue(result.xml.contains("&lt;1&gt;")); assertTrue(result.xml.contains("[REDACTED]"))
        assertFalse(result.xml.contains("a&lt;&amp;\"")); assertTrue(root.released)
    }

    @Test fun boundsNodesAndDepthAreReported() {
        val root = N("r", listOf(N("c"), N("d")))
        val result = MobileUiXmlSerializer.serialize(root, MobileUiXmlSerializer.Limits(maxNodes = 2, maxDepth = 40, maxBytes = 100_000))
        assertTrue(result.truncated); assertEquals(2, result.nodes)
        val deep = generateSequence(N("x")) { N("x", listOf(it)) }.take(3).last()
        assertTrue(MobileUiXmlSerializer.serialize(deep, MobileUiXmlSerializer.Limits(maxDepth = 1)).truncated)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsImpossibleByteLimit() {
        MobileUiXmlSerializer.serialize(N("r"), MobileUiXmlSerializer.Limits(maxBytes = 6))
    }
}
