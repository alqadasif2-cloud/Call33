package app.backup.core

object Csv {
    fun escape(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    fun row(cells: List<String>): String = cells.joinToString(",") { escape(it) }

    fun parse(text: String): List<List<String>> {
        val t = text.removePrefix("\uFEFF")
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < t.length) {
            val ch = t[i]
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < t.length && t[i + 1] == '"') { sb.append('"'); i++ } else inQuotes = false
                } else sb.append(ch)
            } else {
                when (ch) {
                    '"' -> inQuotes = true
                    ',' -> { row.add(sb.toString()); sb.setLength(0) }
                    '\r' -> {}
                    '\n' -> { row.add(sb.toString()); sb.setLength(0); rows.add(row); row = ArrayList() }
                    else -> sb.append(ch)
                }
            }
            i++
        }
        if (sb.isNotEmpty() || row.isNotEmpty()) { row.add(sb.toString()); rows.add(row) }
        return rows
    }
}
