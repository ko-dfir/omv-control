package com.omvcompanion.app.ui.dashboard

/** Bounded VT-style character grid; escape sequences can span SSH reads. */
internal class TerminalBuffer(private val width: Int = 100, private val height: Int = 28) {
    private val lines = MutableList(height) { CharArray(width) { ' ' } }
    private var row = 0
    private var col = 0
    private var escape = ""
    @Synchronized fun feed(text: String): String {
        for (c in text) {
            if (escape.isNotEmpty()) {
                escape += c
                if (escape == "\u001b[") continue
                if (escape.startsWith("\u001b]")) { if (c == '\u0007' || escape.endsWith("\u001b\\")) escape = ""; continue }
                if (escape.startsWith("\u001b[")) {
                    if (c in '@'..'~' && escape.length > 2) { control(escape.substring(2, escape.length - 1), c); escape = "" }
                } else if (escape.length >= 2) escape = ""
                if (escape.length > 1024) escape = ""
                continue
            }
            when (c) {
                '\u001b' -> escape = c.toString()
                '\r' -> col = 0
                '\n' -> down()
                '\b' -> col = (col - 1).coerceAtLeast(0)
                '\t' -> col = ((col / 8 + 1) * 8).coerceAtMost(width - 1)
                else -> if (c >= ' ') { if (col >= width) { col = 0; down() }; lines[row][col++] = c }
            }
        }
        return lines.joinToString("\n") { String(it).trimEnd() }.trimEnd()
    }
    private fun down() { row++; if (row >= height) { lines.removeAt(0); lines.add(CharArray(width) { ' ' }); row = height - 1 } }
    private fun control(args: String, command: Char) {
        val p = args.removePrefix("?").split(';').map { it.toIntOrNull() ?: 0 }
        val n = p.firstOrNull()?.takeIf { it > 0 } ?: 1
        when (command) {
            'A' -> row = (row - n).coerceAtLeast(0)
            'B' -> row = (row + n).coerceAtMost(height - 1)
            'C' -> col = (col + n).coerceAtMost(width - 1)
            'D' -> col = (col - n).coerceAtLeast(0)
            'H', 'f' -> { row = (n - 1).coerceIn(0, height - 1); col = ((p.getOrNull(1) ?: 1).coerceAtLeast(1) - 1).coerceIn(0, width - 1) }
            'G' -> col = (n - 1).coerceIn(0, width - 1)
            'J' -> if (p.firstOrNull() in listOf(2, 3)) lines.forEach { it.fill(' ') }
            'K' -> when (p.firstOrNull() ?: 0) { 0 -> lines[row].fill(' ', col.coerceAtMost(width), width); 1 -> lines[row].fill(' ', 0, (col + 1).coerceAtMost(width)); 2 -> lines[row].fill(' ') }
        }
    }
}
