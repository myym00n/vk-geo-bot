package ru.geo.vkbot

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.nio.charset.Charset
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream

/**
 * Разбор официального архива данных ВКонтакте (zip):
 * Archive/messages/<peer_id>/messagesN.html + messages/index-messages.html.
 */
object Archive {

    data class Conv(val id: String, val name: String, val pages: Int) {
        val isChat get() = (id.toLongOrNull() ?: 0L) > 2_000_000_000L
    }

    data class Msg(val author: String, val uid: String, val date: LocalDateTime?, val text: String, val order: Int)

    data class Author(val uid: String, val name: String, val total: Int, val withText: Int)

    // Состояние экрана живёт здесь, чтобы не терялось при переключении вкладок
    val uri = MutableStateFlow<Uri?>(null)
    val convs = MutableStateFlow<List<Conv>>(emptyList())
    val conv = MutableStateFlow<Conv?>(null)
    val messages = MutableStateFlow<List<Msg>>(emptyList())
    val authors = MutableStateFlow<List<Author>>(emptyList())
    val author = MutableStateFlow<Author?>(null)
    val busy = MutableStateFlow(false)
    val status = MutableStateFlow("")

    private val pathRe = Regex("(?:^|/)messages/(-?\\d+)/messages(\\d+)\\.html$", RegexOption.IGNORE_CASE)
    private val indexRe = Regex("(?:^|/)messages/index-messages\\.html$", RegexOption.IGNORE_CASE)
    private val cp1251: Charset = Charset.forName("windows-1251")

    private fun decode(bytes: ByteArray): String {
        val head = String(bytes, 0, minOf(bytes.size, 1500), Charsets.US_ASCII).lowercase()
        val utf = Regex("charset=[\"']?utf-?8").containsMatchIn(head)
        return String(bytes, if (utf) Charsets.UTF_8 else cp1251)
    }

    /** Проход 1: список бесед и их названия. */
    suspend fun scan(context: Context, u: Uri): List<Conv> = withContext(Dispatchers.IO) {
        val pages = mutableMapOf<String, Int>()
        val names = mutableMapOf<String, String>()
        val crumbNames = mutableMapOf<String, String>()
        var seen = 0
        context.contentResolver.openInputStream(u)!!.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    val name = e.name.replace('\\', '/')
                    val m = pathRe.find(name)
                    when {
                        m != null -> {
                            val id = m.groupValues[1]
                            pages[id] = (pages[id] ?: 0) + 1
                            if (m.groupValues[2] == "0" && id !in crumbNames) {
                                runCatching {
                                    val doc = Jsoup.parse(decode(zip.readBytes()))
                                    doc.select(".ui_crumb").lastOrNull()?.text()?.trim()
                                        ?.takeIf { it.isNotBlank() && it != "Сообщения" }
                                        ?.let { crumbNames[id] = it }
                                }
                            }
                            seen++
                            if (seen % 200 == 0) status.value = "Читаю архив… файлов: $seen"
                        }
                        indexRe.containsMatchIn(name) -> runCatching {
                            val doc = Jsoup.parse(decode(zip.readBytes()))
                            doc.select("a[href]").forEach { a ->
                                val hm = Regex("^(-?\\d+)/messages\\d*\\.html").find(a.attr("href"))
                                if (hm != null && a.text().isNotBlank()) names[hm.groupValues[1]] = a.text().trim()
                            }
                        }
                    }
                }
            }
        }
        pages.map { (id, n) ->
            val long = id.toLongOrNull() ?: 0L
            val fallback = if (long > 2_000_000_000L) "Беседа №${long - 2_000_000_000L}" else "Диалог $id"
            Conv(id, names[id] ?: crumbNames[id] ?: fallback, n)
        }.sortedWith(compareByDescending<Conv> { it.isChat }.thenByDescending { it.pages })
    }

    /** Проход 2: все сообщения одной беседы. */
    suspend fun load(context: Context, u: Uri, convId: String): List<Msg> = withContext(Dispatchers.IO) {
        data class Page(val n: Int, val msgs: List<Msg>)
        val result = mutableListOf<Page>()
        var order = 0
        context.contentResolver.openInputStream(u)!!.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val m = pathRe.find(e.name.replace('\\', '/')) ?: continue
                    if (m.groupValues[1] != convId) continue
                    val doc = Jsoup.parse(decode(zip.readBytes()))
                    val list = doc.select("div.message").mapNotNull { el -> parseMessage(el, order++) }
                    result += Page(m.groupValues[2].toInt(), list)
                    status.value = "Читаю беседу… страниц: ${result.size}"
                }
            }
        }
        // внутри файла и между файлами ВК идёт от новых к старым; сортируем по дате, без даты — по порядку файлов
        val flat = result.sortedBy { it.n }.flatMap { it.msgs }
        flat.withIndex().sortedWith(
            compareBy<IndexedValue<Msg>> { it.value.date ?: LocalDateTime.MIN }.thenByDescending { it.index },
        ).map { it.value }
    }

    private fun parseMessage(el: Element, order: Int): Msg? {
        val header = el.selectFirst(".message__header") ?: return null
        val a = header.selectFirst("a")
        val htext = header.text().replace(Regex("\\s+"), " ").trim()
        val author = a?.text()?.trim()?.ifBlank { null } ?: htext.substringBefore(',').trim()
        val uid = a?.attr("href")?.let { Regex("id(\\d+)").find(it)?.groupValues?.get(1) }
            ?: if (author == "Вы") "me" else author
        val body = el.clone()
        body.select(".kludges, .attachment, .message__header").remove()
        body.select("br").forEach { it.replaceWith(TextNode("\n")) }
        val text = body.wholeText().replace(' ', ' ')
            .lines().joinToString("\n") { it.trim() }
            .replace(Regex("\\n{3,}"), "\n\n").trim()
        return Msg(author, uid, parseDate(htext), text, order)
    }

    private val months = mapOf(
        "янв" to 1, "фев" to 2, "мар" to 3, "апр" to 4, "мая" to 5, "май" to 5, "июн" to 6,
        "июл" to 7, "авг" to 8, "сен" to 9, "окт" to 10, "ноя" to 11, "дек" to 12,
    )

    fun parseDate(s: String): LocalDateTime? {
        val m = Regex("(\\d{1,2})\\s+([а-яё]+)\\.?\\s+(\\d{4})\\s*(?:г\\.)?\\s*(?:в\\s+)?(\\d{1,2}):(\\d{2})(?::(\\d{2}))?", RegexOption.IGNORE_CASE)
            .find(s) ?: return null
        val mon = months[m.groupValues[2].lowercase().take(3)] ?: return null
        return runCatching {
            LocalDateTime.of(
                m.groupValues[3].toInt(), mon, m.groupValues[1].toInt(),
                m.groupValues[4].toInt(), m.groupValues[5].toInt(), m.groupValues[6].ifBlank { "0" }.toInt(),
            )
        }.getOrNull()
    }

    fun authorsOf(msgs: List<Msg>): List<Author> =
        msgs.groupBy { it.uid }.map { (uid, l) ->
            Author(uid, l.last().author, l.size, l.count { it.text.isNotBlank() })
        }.sortedByDescending { it.withText }

    private val dFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val tFmt = DateTimeFormatter.ofPattern("HH:mm")

    fun parseUserDate(s: String): LocalDate? =
        runCatching { LocalDate.parse(s.trim(), dFmt) }.getOrNull()

    fun filter(msgs: List<Msg>, a: Author, from: LocalDate?, to: LocalDate?): List<Msg> =
        msgs.filter { m ->
            m.uid == a.uid && m.text.isNotBlank() &&
                (from == null || (m.date != null && !m.date.toLocalDate().isBefore(from))) &&
                (to == null || (m.date != null && !m.date.toLocalDate().isAfter(to)))
        }

    fun stamp(d: LocalDateTime?, time: Boolean = true): String =
        if (d == null) "—" else d.format(dFmt) + if (time) " " + d.format(tFmt) else ""

    fun toTxt(a: Author, c: Conv, list: List<Msg>, time: Boolean): String =
        "${a.name} — «${c.name}»\nСообщений: ${list.size}\n\n" +
            list.joinToString("\n\n") { "[${stamp(it.date, time)}]\n${it.text}" } + "\n"

    fun toCsv(list: List<Msg>): String {
        fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val rows = mutableListOf(listOf("Дата", "Время", "Автор", "Текст").joinToString(";") { q(it) })
        list.forEach { m ->
            rows += listOf(
                m.date?.format(dFmt) ?: "", m.date?.format(tFmt) ?: "", m.author, m.text,
            ).joinToString(";") { q(it) }
        }
        return "﻿" + rows.joinToString("\r\n")
    }

    fun fileName(a: Author, c: Conv, ext: String): String {
        fun safe(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]+"), "").replace(Regex("\\s+"), "_").take(50)
        return "${safe(a.name)}_${safe(c.name)}.$ext"
    }
}
