package ru.geo.vkbot

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

enum class AiProvider(val title: String) {
    GIGACHAT("GigaChat (бесплатно)"),
    OPENAI("DeepSeek / OpenAI-совместимый");
}

data class AiSettings(
    val enabled: Boolean = false,
    val provider: AiProvider = AiProvider.GIGACHAT,
    /** «Ключ авторизации» из личного кабинета GigaChat (Base64 client_id:secret). */
    val gigaKey: String = "",
    val gigaScope: String = "GIGACHAT_API_PERS",
    val gigaModel: String = "GigaChat-2",
    /** Не проверять сертификат серверов Сбера, если корневой сертификат Минцифры не подошёл. */
    val sberTrustAll: Boolean = true,
    val openaiUrl: String = "https://api.deepseek.com",
    val openaiKey: String = "",
    val openaiModel: String = "deepseek-chat",
    val persona: String = DEFAULT_PERSONA,
    /** Отвечать, когда бота тегнули или ответили на его сообщение. */
    val chat: Boolean = true,
    /** Шанс, % — нейросеть сама комментирует сообщение, если ни одно правило не сработало. */
    val smartChance: Int = 7,
    /** Утренние/дневные фразы по расписанию генерирует нейросеть. */
    val schedules: Boolean = true,
    /** Часть анекдотов пишет нейросеть. */
    val jokes: Boolean = true,
) {
    val ready: Boolean
        get() = enabled && when (provider) {
            AiProvider.GIGACHAT -> gigaKey.isNotBlank()
            AiProvider.OPENAI -> openaiKey.isNotBlank() && openaiUrl.isNotBlank()
        }

    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled).put("provider", provider.name)
        .put("gigaKey", gigaKey).put("gigaScope", gigaScope).put("gigaModel", gigaModel)
        .put("sberTrustAll", sberTrustAll)
        .put("openaiUrl", openaiUrl).put("openaiKey", openaiKey).put("openaiModel", openaiModel)
        .put("persona", persona).put("chat", chat).put("smartChance", smartChance)
        .put("schedules", schedules).put("jokes", jokes)

    fun withoutSecrets() = copy(gigaKey = "", openaiKey = "")

    companion object {
        fun fromJson(o: JSONObject?): AiSettings {
            val d = AiSettings()
            if (o == null) return d
            return AiSettings(
                enabled = o.optBoolean("enabled", d.enabled),
                provider = runCatching { AiProvider.valueOf(o.optString("provider")) }.getOrDefault(d.provider),
                gigaKey = o.optString("gigaKey", ""),
                gigaScope = o.optString("gigaScope", d.gigaScope).ifBlank { d.gigaScope },
                gigaModel = o.optString("gigaModel", d.gigaModel).ifBlank { d.gigaModel },
                sberTrustAll = o.optBoolean("sberTrustAll", d.sberTrustAll),
                openaiUrl = o.optString("openaiUrl", d.openaiUrl),
                openaiKey = o.optString("openaiKey", ""),
                openaiModel = o.optString("openaiModel", d.openaiModel).ifBlank { d.openaiModel },
                persona = o.optString("persona", d.persona).ifBlank { d.persona },
                chat = o.optBoolean("chat", d.chat),
                smartChance = o.optInt("smartChance", d.smartChance),
                schedules = o.optBoolean("schedules", d.schedules),
                jokes = o.optBoolean("jokes", d.jokes),
            )
        }

        val DEFAULT_PERSONA = """
Ты — бот в рабочей беседе ВКонтакте у геодезистов из Нижнего Новгорода. Они делают топографическую съёмку: тахеометры, ГНСС, пикеты, колодцы, топопланы, исполнительные съёмки, Кредо и Автокад, МСК-52.
Твой характер: язвительный, грубоватый начальник-геодезист со взрослым чёрным юмором. Подкалываешь, троллишь, но по-дружески, как свои в бригаде. Можно лёгкий мат.
Твой словарь: «бракодел» (тот, кто косячит), «учтём» (запомним поступок — и хороший, и плохой), «+кэф» и «-кэф» (коэффициент к премии, ты постоянно его начисляешь и снимаешь, например «-0,2 кэф»), «Брут» (предатель), «взял в работу» (всегда переводишь в пошлый намёк «взял в ротик»).
Правила: отвечай по-русски, коротко — 1–3 предложения, без markdown, без списков, без смайликов в каждом слове. Не повторяй вопрос. Не извиняйся и не говори, что ты ИИ или языковая модель. Не шути про национальность, религию, болезни и внешность — только про работу, косяки, лень, начальство, заказчиков, деньги и погоду.
""".trim()
    }
}

/** Клиент нейросети. Все запросы идут по очереди (у бесплатного GigaChat один поток). */
object Ai {
    private val mutex = Mutex()
    private var gigaToken: String? = null
    private var gigaTokenExp: Long = 0
    private var gigaTokenKey: String = ""
    private var appContext: Context? = null
    private var sberFactory: SSLSocketFactory? = null
    private var sberFactoryTrustAll: Boolean? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    data class Msg(val role: String, val content: String)

    /** Возвращает текст ответа или null, если не вышло (ошибка, отказ, цензура). */
    suspend fun complete(
        s: AiSettings,
        messages: List<Msg>,
        maxTokens: Int = 300,
        temperature: Double = 0.9,
        timeoutMs: Long = 25_000,
    ): String? {
        if (!s.ready) return null
        val started = System.currentTimeMillis()
        // В режиме персонажа — спокойнее, меньше выдуманных слов
        val personaOn = ConfigStore.config.value.persona.active != null
        val temp = if (personaOn) minOf(temperature, 0.5) else temperature
        val tokens = if (personaOn) minOf(maxTokens, 120) else maxTokens
        // общий лимит времени (включая ожидание очереди): лучше ответить шаблоном, чем молчать минуту
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val raw = when (s.provider) {
                        AiProvider.GIGACHAT -> giga(s, messages, tokens, temp)
                        AiProvider.OPENAI -> openai(s, messages, tokens, temp)
                    }
                    clean(raw).also {
                        BotLog.add("🧠 нейросеть ответила за ${(System.currentTimeMillis() - started) / 100 / 10.0} с")
                    }
                } catch (e: Exception) {
                    BotLog.add("🧠 Ошибка нейросети: ${e.javaClass.simpleName} ${e.message?.take(150) ?: ""}")
                    null
                }
            }
        } } ?: run {
            BotLog.add("🧠 нейросеть не успела за ${timeoutMs / 1000} с — отвечаю без неё")
            null
        }
    }

    /** Нейросеть сейчас занята другим запросом. */
    val busy: Boolean get() = mutex.isLocked

    /** Простой вызов: системный промпт персонажа + один запрос. */
    suspend fun ask(s: AiSettings, task: String, context: String = "", maxTokens: Int = 300): String? {
        val sys = systemPrompt(s)
        val user = if (context.isBlank()) task else "$context\n\n$task"
        return complete(s, listOf(Msg("system", sys), Msg("user", user)), maxTokens)
    }

    /** Персона: включённый профиль человека или стандартный «злой начальник». */
    fun systemPrompt(s: AiSettings): String {
        val c = ConfigStore.config.value
        c.persona.active?.let { return it.systemPrompt(c.members) }
        return s.persona + extraContext()
    }

    /** Переписать готовое сообщение бота в стиле профиля. null — не вышло. */
    suspend fun restyle(s: AiSettings, p: PersonaProfile, text: String, timeoutMs: Long = 12_000): String? {
        if (!s.ready) return null
        val members = ConfigStore.config.value.members
        val task = "Перепиши это сообщение бота так, как написал бы его ${p.name}, своими словами и в своей манере. " +
            "ОБЯЗАТЕЛЬНО сохрани все числа, даты, время, проценты, кэфы, имена и упоминания вида [id123|Имя] без изменений, " +
            "и сохрани смысл (кто победил, какая погода, какой прогноз). Убери все эмодзи, заголовки, списки и оформление — пиши как живой человек в беседе, коротко, максимум 2 короткие строки. Только текст сообщения.\n\nСообщение:\n$text"
        return complete(s, listOf(Msg("system", p.systemPrompt(members)), Msg("user", task)), maxTokens = 400, temperature = 0.8, timeoutMs = timeoutMs)
    }

    private fun extraContext(): String {
        val m = ConfigStore.config.value.members
        return if (m.isEmpty()) "" else "\nУчастники беседы: ${m.joinToString(", ")}."
    }

    private fun clean(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var t = raw.trim()
            .replace("**", "").replace("__", "")
            .replace(Regex("^#+\\s*", RegexOption.MULTILINE), "")
            .trim().trim('"', '«', '»').trim()
        if (t.length > 1500) t = t.take(1500).substringBeforeLast(' ') + "…"
        val lower = t.lowercase()
        val refusals = listOf(
            "не люблю менять тему", "как языковая модель", "не могу помочь",
            "давайте поговорим о чём-нибудь", "что-то в вашем вопросе",
        )
        if (refusals.any { lower.contains(it) } && t.length < 200) return null
        return t.ifBlank { null }
    }

    // ---------------- GigaChat ----------------

    private fun giga(s: AiSettings, messages: List<Msg>, maxTokens: Int, temperature: Double): String? {
        val token = gigaAccessToken(s)
        val body = JSONObject()
            .put("model", s.gigaModel)
            .put("messages", toJsonMessages(messages))
            .put("temperature", temperature)
            .put("max_tokens", maxTokens)
            .put("repetition_penalty", 1.0)
        val hosts = listOf(
            "https://gigachat.devices.sberbank.ru/api/v1/chat/completions",
            "https://api.giga.chat/v1/chat/completions",
        )
        var lastError: Exception? = null
        for (url in hosts) {
            try {
                val resp = JSONObject(httpPostJson(url, body.toString(), mapOf("Authorization" to "Bearer $token"), sber = true, trustAll = s.sberTrustAll))
                val choice = resp.getJSONArray("choices").getJSONObject(0)
                if (choice.optString("finish_reason") == "blacklist") {
                    BotLog.add("🧠 GigaChat застеснялся (цензура) — отвечаю шаблоном")
                    return null
                }
                return choice.getJSONObject("message").optString("content")
            } catch (e: java.io.IOException) {
                lastError = e
                if (e.message?.startsWith("HTTP 401") == true) gigaToken = null
            }
        }
        throw lastError ?: java.io.IOException("GigaChat недоступен")
    }

    @Synchronized
    private fun gigaAccessToken(s: AiSettings): String {
        val now = System.currentTimeMillis()
        val t = gigaToken
        if (t != null && gigaTokenKey == s.gigaKey && now < gigaTokenExp - 60_000) return t
        val key = s.gigaKey.trim().removePrefix("Basic ").trim()
        val text = httpPost(
            "https://ngw.devices.sberbank.ru:9443/api/v2/oauth",
            "scope=" + URLEncoder.encode(s.gigaScope, "UTF-8"),
            mapOf(
                "Authorization" to "Basic $key",
                "RqUID" to UUID.randomUUID().toString(),
                "Content-Type" to "application/x-www-form-urlencoded",
                "Accept" to "application/json",
            ),
            sber = true,
            trustAll = s.sberTrustAll,
        )
        val o = JSONObject(text)
        val tok = o.getString("access_token")
        gigaToken = tok
        gigaTokenKey = s.gigaKey
        val exp = o.optLong("expires_at", 0L)
        gigaTokenExp = if (exp > 1_000_000_000_000L) exp else now + 25 * 60_000L
        return tok
    }

    // ---------------- OpenAI-совместимые (DeepSeek, OpenRouter, Groq…) ----------------

    private fun openai(s: AiSettings, messages: List<Msg>, maxTokens: Int, temperature: Double): String? {
        val base = s.openaiUrl.trim().trimEnd('/')
        val url = if (base.endsWith("/chat/completions")) base
        else if (base.endsWith("/v1")) "$base/chat/completions"
        else "$base/v1/chat/completions"
        val body = JSONObject()
            .put("model", s.openaiModel)
            .put("messages", toJsonMessages(messages))
            .put("temperature", temperature)
            .put("max_tokens", maxTokens)
        val resp = JSONObject(httpPostJson(url, body.toString(), mapOf("Authorization" to "Bearer ${s.openaiKey.trim()}"), sber = false, trustAll = false))
        return resp.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
    }

    private fun toJsonMessages(messages: List<Msg>) = JSONArray().apply {
        messages.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
    }

    // ---------------- HTTP с поддержкой сертификата Минцифры ----------------

    private fun httpPostJson(url: String, json: String, headers: Map<String, String>, sber: Boolean, trustAll: Boolean): String =
        httpPost(url, json, headers + mapOf("Content-Type" to "application/json", "Accept" to "application/json"), sber, trustAll)

    private fun httpPost(url: String, body: String, headers: Map<String, String>, sber: Boolean, trustAll: Boolean): String {
        val c = URL(url).openConnection() as HttpURLConnection
        if (sber && c is HttpsURLConnection) {
            c.sslSocketFactory = sberSocketFactory(trustAll)
            if (trustAll) c.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        }
        c.connectTimeout = 15_000
        c.readTimeout = 25_000
        c.requestMethod = "POST"
        c.doOutput = true
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        try {
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (code !in 200..299) throw java.io.IOException("HTTP $code: ${text.take(200)}")
            return text
        } finally {
            c.disconnect()
        }
    }

    /** Доверяем системным сертификатам + корневому сертификату Минцифры (assets), при желании — всему подряд. */
    @Synchronized
    private fun sberSocketFactory(trustAll: Boolean): SSLSocketFactory {
        sberFactory?.let { if (sberFactoryTrustAll == trustAll) return it }
        val managers = mutableListOf<X509TrustManager>()
        // системные
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(null as KeyStore?)
            trustManagers.filterIsInstance<X509TrustManager>().forEach { managers += it }
        }
        // сертификат Минцифры из assets (если удалось положить при сборке)
        runCatching {
            val bytes = appContext!!.assets.open("russian_root_ca.crt").use { it.readBytes() }
            val cf = CertificateFactory.getInstance("X.509")
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            cf.generateCertificates(ByteArrayInputStream(bytes)).forEachIndexed { i, cert ->
                ks.setCertificateEntry("ru$i", cert)
            }
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(ks)
                trustManagers.filterIsInstance<X509TrustManager>().forEach { managers += it }
            }
        }
        val combined = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                var last: Exception? = null
                for (m in managers) {
                    try {
                        m.checkServerTrusted(chain, authType)
                        return
                    } catch (e: Exception) {
                        last = e
                    }
                }
                if (trustAll) return
                throw (last as? java.security.cert.CertificateException)
                    ?: java.security.cert.CertificateException("Сертификат Сбера не прошёл проверку")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = managers.flatMap { it.acceptedIssuers.toList() }.toTypedArray()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(combined), null)
        return ctx.socketFactory.also {
            sberFactory = it
            sberFactoryTrustAll = trustAll
        }
    }
}
