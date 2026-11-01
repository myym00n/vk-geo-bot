package ru.geo.vkbot

/** Делает сообщения бота похожими на сообщения живого человека. */
object Humanize {

    private fun isEmoji(cp: Int): Boolean =
        cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF ||
            cp in 0x2500..0x257F || cp == 0xFE0F || cp == 0x200D || cp == 0x20E3 ||
            cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F || cp == 0x2122 || cp == 0x00A9

    fun stripEmoji(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (!isEmoji(cp)) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    fun clean(text: String): String {
        var t = stripEmoji(text)
            .replace("**", "").replace("__", "").replace("`", "")
        t = t.lines().joinToString("\n") { line ->
            line.trim()
                .replace(Regex("^#+\\s*"), "")
                .replace(Regex("^[•\\-–—*]\\s+"), "")
                .replace(Regex("^\\d+[.)]\\s+"), "")
                .replace(Regex("^(ИТОГИ ДНЯ|ТОП ПО КЭФУ:?|ВИКТОРИНА.*?\\)|ДУЭЛЬ:?)\\s*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("^(Анекдот по расписанию|Оружие):\\s*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s{2,}"), " ")
                .trim()
                .trim('"', '«', '»')
        }
        return t.replace(Regex("\\n{2,}"), "\n").trim()
    }

    /** Режем на 1–4 коротких сообщения, без точки в конце — как пишут в беседах. */
    fun split(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        var lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size == 1 && lines[0].length > 110) {
            lines = lines[0].split(Regex("(?<=[.!?…])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        }
        val out = mutableListOf<String>()
        val maxParts = 4
        if (lines.size <= maxParts) {
            out += lines
        } else {
            val per = (lines.size + maxParts - 1) / maxParts
            lines.chunked(per).forEach { out += it.joinToString("\n") }
        }
        return out.map { p ->
            var s = p
            if (s.endsWith(".") && !s.endsWith("..")) s = s.dropLast(1)
            s
        }.filter { it.isNotBlank() }
    }
}
