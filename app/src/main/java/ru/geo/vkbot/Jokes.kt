package ru.geo.vkbot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.Charset

/** Анекдоты с rzhunemogu.ru, при недоступности — свои из настроек. */
object Jokes {
    private val recent = ArrayDeque<String>()
    private val cp1251: Charset = Charset.forName("windows-1251")

    private suspend fun fromSite(adult: Boolean): String? = withContext(Dispatchers.IO) {
        // CType: 1 — анекдоты, 11 — анекдоты 18+
        val type = if (adult) 11 else 1
        val raw = runCatching {
            Http.get("http://rzhunemogu.ru/RandJSON.aspx?CType=$type", charset = cp1251)
        }.getOrNull() ?: return@withContext null
        // Ответ вида {"content":"текст"}, но с «сырыми» переводами строк — парсим вручную
        val start = raw.indexOf("\"content\"")
        if (start < 0) return@withContext null
        val q1 = raw.indexOf('"', raw.indexOf(':', start) + 1)
        val q2 = raw.lastIndexOf('"')
        if (q1 < 0 || q2 <= q1) return@withContext null
        raw.substring(q1 + 1, q2)
            .replace("\\n", "\n").replace("\\\"", "\"").replace("\r", "")
            .trim()
            .takeIf { it.length in 10..3000 }
    }

    suspend fun random(s: JokeSettings): String {
        val own = s.ownJokes
        // примерно каждый четвёртый — свой геодезический
        val preferOwn = own.isNotEmpty() && kotlin.random.Random.nextInt(4) == 0
        if (!preferOwn) {
            repeat(3) {
                val j = fromSite(s.adult)
                if (j != null && j !in recent) {
                    remember(j)
                    return j
                }
            }
        }
        if (own.isEmpty()) return "Анекдоты кончились. Как и кэф."
        val fresh = own.filter { it !in recent }.ifEmpty { own }
        return fresh.random().also { remember(it) }
    }

    private fun remember(j: String) {
        recent.addLast(j)
        while (recent.size > 30) recent.removeFirst()
    }
}
