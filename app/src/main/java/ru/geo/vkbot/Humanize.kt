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

    /** Капс — только изредка и только его реальные «капсовые» фразы. */
    private fun calmCaps(line: String, keep: Set<String>): String {
        val letters = line.filter { it.isLetter() }
        if (letters.length < 4) return line
        val upper = letters.count { it.isUpperCase() }
        if (upper < letters.length * 0.7) return line
        if (line.trim() in keep) return line
        if (kotlin.random.Random.nextInt(100) < 8) return line
        val low = line.lowercase()
        val fixed = low.replaceFirstChar { it.uppercase() }
            .replace(Regex("!{2,}"), if (kotlin.random.Random.nextBoolean()) "" else "!")
            .replace(Regex("\\?{2,}"), "?")
            .replace(Regex("\\?!+|!\\?+"), "?")
        // имена коллег — с большой буквы
        return ConfigStore.config.value.members.flatMap { it.split(' ') }.filter { it.length > 2 }
            .fold(fixed) { acc, n -> acc.replace(Regex("(?<![\\p{L}])" + Regex.escape(n.lowercase()) + "(?![\\p{L}])"), n) }
    }

    /** Кэф в тексте — в пределах ±0,5, как в его реальных сообщениях. */
    private fun clampKef(s: String): String =
        Regex("([+\\-−–])\\s?(\\d+(?:[.,]\\d+)?)(\\s*(?:к[эе]ф|коэф\\w*))", RegexOption.IGNORE_CASE).replace(s) { m ->
            val v = m.groupValues[2].replace(',', '.').toDoubleOrNull() ?: return@replace m.value
            if (v <= 0.5) m.value
            else m.groupValues[1] + " " + listOf("0,1", "0,2", "0,3", "0,5").random() + m.groupValues[3]
        }

    /** Ключ для сравнения строк: без регистра, знаков и пробелов. */
    fun key(s: String): String = s.lowercase().replace('ё', 'е').filter { it.isLetterOrDigit() }

    fun clean(text: String, name: String = "", keepCaps: Set<String> = emptySet()): String {
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
        // «Артём:» в начале, подписи, склеенная тарабарщина
        if (name.isNotBlank()) {
            val n = Regex.escape(name)
            val nameRe = Regex("^\\s*(" + n + "|" + n.replace("ё", "е") + ")(\\s+\\p{L}+)?\\s*[:—-]\\s*", RegexOption.IGNORE_CASE)
            t = t.lines().map { it.replace(nameRe, "") }
                .filter { !it.trim().equals(name, true) && !it.trim().equals("$name:", true) }
                .joinToString("\n")
        }
        t = t.lines()
            .filter { line -> line.split(Regex("\\s+")).none { w -> w.count { it.isLetter() } > 22 } }
            .joinToString("\n") { calmCaps(clampKef(it), keepCaps) }
        return t.replace(Regex("\\n{2,}"), "\n").trim()
    }

    /** Режем на 1–4 коротких сообщения, без точки в конце — как пишут в беседах. */
    fun split(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        var lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size == 1 && lines[0].length > 110) {
            lines = lines[0].split(Regex("(?<=[.!?…])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        }
        // Живой человек не пишет простыни: максимум 3 строки, остальное отбрасываем
        lines = lines.take(3)
        val out = mutableListOf<String>()
        val maxParts = 2
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
