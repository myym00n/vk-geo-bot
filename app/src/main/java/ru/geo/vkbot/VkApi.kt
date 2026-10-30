package ru.geo.vkbot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import kotlin.random.Random

class VkException(val code: Int, message: String) : Exception(message)

/** Простые HTTP-запросы без сторонних библиотек. */
object Http {
    fun get(url: String, timeoutMs: Int = 20_000, charset: Charset = Charsets.UTF_8): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = timeoutMs
        c.setRequestProperty("User-Agent", "VkGeoBot/1.0 (Android)")
        try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.use { it.readBytes().toString(charset) } ?: ""
            if (code !in 200..299) throw java.io.IOException("HTTP $code: ${body.take(200)}")
            return body
        } finally {
            c.disconnect()
        }
    }

    fun postForm(url: String, params: Map<String, String>, timeoutMs: Int = 30_000): String {
        val body = params.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }.toByteArray(Charsets.UTF_8)
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = timeoutMs
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
        try {
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (code !in 200..299) throw java.io.IOException("HTTP $code: ${text.take(200)}")
            return text
        } finally {
            c.disconnect()
        }
    }
}

class VkApi(private val token: String, private val groupId: Long) {
    private val version = "5.199"

    suspend fun call(method: String, params: Map<String, String> = emptyMap()): Any =
        withContext(Dispatchers.IO) {
            val all = params + mapOf("access_token" to token, "v" to version)
            val text = Http.postForm("https://api.vk.com/method/$method", all)
            val o = JSONObject(text)
            if (o.has("error")) {
                val e = o.getJSONObject("error")
                throw VkException(e.optInt("error_code"), e.optString("error_msg"))
            }
            o.get("response")
        }

    data class LongPollServer(val server: String, val key: String, var ts: String)

    suspend fun getLongPollServer(): LongPollServer {
        val r = call("groups.getLongPollServer", mapOf("group_id" to groupId.toString())) as JSONObject
        return LongPollServer(r.getString("server"), r.getString("key"), r.getString("ts"))
    }

    /** Возвращает сырой ответ long poll сервера. */
    suspend fun poll(s: LongPollServer, wait: Int = 25): JSONObject = withContext(Dispatchers.IO) {
        val url = "${s.server}?act=a_check&key=${URLEncoder.encode(s.key, "UTF-8")}&ts=${s.ts}&wait=$wait"
        JSONObject(Http.get(url, timeoutMs = (wait + 15) * 1000))
    }

    suspend fun send(peerId: Long, text: String, replyTo: Int? = null) {
        val p = mutableMapOf(
            "peer_id" to peerId.toString(),
            "message" to text,
            "random_id" to Random.nextInt(1, Int.MAX_VALUE).toString(),
            "disable_mentions" to "0",
        )
        if (replyTo != null && replyTo > 0) {
            // forward с is_reply — цитата в беседе (conversation_message_id)
            p["forward"] = JSONObject()
                .put("peer_id", peerId)
                .put("conversation_message_ids", org.json.JSONArray().put(replyTo))
                .put("is_reply", true)
                .toString()
        }
        call("messages.send", p)
    }

    private val nameCache = java.util.concurrent.ConcurrentHashMap<Long, String>()

    suspend fun firstName(userId: Long): String {
        if (userId <= 0) return "бот"
        nameCache[userId]?.let { return it }
        return runCatching {
            val arr = call("users.get", mapOf("user_ids" to userId.toString())) as org.json.JSONArray
            arr.getJSONObject(0).optString("first_name", "Аноним")
        }.getOrDefault("Аноним").also { nameCache[userId] = it }
    }

    /** Имена участников беседы (нужны права администратора у бота). */
    suspend fun chatMemberNames(peerId: Long): List<String> = runCatching {
        val r = call("messages.getConversationMembers", mapOf("peer_id" to peerId.toString())) as JSONObject
        val profiles = r.optJSONArray("profiles") ?: return@runCatching emptyList<String>()
        List(profiles.length()) {
            val p = profiles.getJSONObject(it)
            "${p.optString("first_name")} ${p.optString("last_name")}".trim()
        }.filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    suspend fun groupName(): String {
        val r = call("groups.getById", mapOf("group_id" to groupId.toString()))
        val arr = when (r) {
            is JSONObject -> r.optJSONArray("groups")
            is org.json.JSONArray -> r
            else -> null
        }
        return arr?.optJSONObject(0)?.optString("name") ?: "?"
    }
}
