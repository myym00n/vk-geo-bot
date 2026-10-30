package ru.geo.vkbot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import kotlin.random.Random

/** Вся логика бота: приём сообщений, правила, расписание, погода, анекдоты. */
class BotEngine(private val scope: CoroutineScope) {

    private var api: VkApi? = null
    private var apiKey = ""

    private val cooldowns = mutableMapOf<String, Long>()
    private val scheduleFired = mutableMapOf<String, LocalDate>()
    private var nextJokeAt: LocalDateTime = LocalDateTime.now().plusMinutes(30)
    private var nextWeatherCheck: LocalDateTime = LocalDateTime.now().plusMinutes(1)
    private var alertSuppressedUntil: LocalDateTime = LocalDateTime.MIN
    private var forecastCache: Pair<Long, Weather.Forecast>? = null

    private val cfg get() = ConfigStore.config.value

    fun start() {
        scope.launch { pollLoop() }
        scope.launch { scheduleLoop() }
    }

    private fun apiOrNull(): VkApi? {
        val c = cfg
        if (c.token.isBlank() || c.groupId == 0L) return null
        val key = "${c.token}|${c.groupId}"
        if (key != apiKey || api == null) {
            api = VkApi(c.token, c.groupId)
            apiKey = key
        }
        return api
    }

    // ---------------- Long Poll ----------------

    private suspend fun pollLoop() {
        var backoff = 5_000L
        while (scope.isActive) {
            val vk = apiOrNull()
            if (vk == null) {
                BotLog.status.value = "Нет токена или ID группы — заполни настройки"
                delay(10_000)
                continue
            }
            try {
                BotLog.status.value = "Подключаюсь…"
                val name = runCatching { vk.groupName() }.getOrDefault("группа ${cfg.groupId}")
                val server = vk.getLongPollServer()
                BotLog.status.value = "✅ Работает: $name"
                BotLog.add("Подключился к Long Poll ($name)")
                backoff = 5_000L
                while (scope.isActive && apiOrNull() === vk) {
                    val r = vk.poll(server)
                    if (r.has("failed")) {
                        when (r.optInt("failed")) {
                            1 -> server.ts = r.optString("ts", server.ts)
                            else -> break // ключ протух — переподключаемся
                        }
                        continue
                    }
                    server.ts = r.optString("ts", server.ts)
                    val updates = r.optJSONArray("updates") ?: continue
                    for (i in 0 until updates.length()) {
                        val u = updates.getJSONObject(i)
                        scope.launch {
                            try {
                                handleUpdate(vk, u)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                BotLog.add("⚠️ Ошибка обработки: ${e.message}")
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: VkException) {
                BotLog.status.value = "❌ Ошибка ВК ${e.code}: ${e.message}"
                BotLog.add("❌ ВК ${e.code}: ${e.message}")
                delay(if (e.code == 5 || e.code == 100) 60_000 else backoff)
                backoff = (backoff * 2).coerceAtMost(120_000)
            } catch (e: Exception) {
                BotLog.status.value = "📡 Нет связи, переподключаюсь…"
                BotLog.add("📡 Связь: ${e.javaClass.simpleName} ${e.message ?: ""}")
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(120_000)
            }
        }
    }

    private suspend fun handleUpdate(vk: VkApi, u: JSONObject) {
        if (u.optString("type") != "message_new") return
        val obj = u.optJSONObject("object") ?: return
        val msg = obj.optJSONObject("message") ?: obj
        val peer = msg.optLong("peer_id")
        val from = msg.optLong("from_id")
        val cmid = msg.optInt("conversation_message_id")
        val rawText = msg.optString("text")
        if (peer == 0L) return

        rememberPeer(peer)
        BotLog.received.value = BotLog.received.value + 1

        // Бота добавили в беседу / новый участник
        msg.optJSONObject("action")?.let { action ->
            val type = action.optString("type")
            val member = action.optLong("member_id")
            if (type == "chat_invite_user" || type == "chat_invite_user_by_link") {
                if (member == -cfg.groupId) {
                    cfg.greeting.randomOrNull()?.let { send(vk, peer, fill(it, peer, "")) }
                } else if (member > 0) {
                    val n = vk.firstName(member)
                    send(vk, peer, "Новенький! $n, держи стартовый кэф 0. Дальше только вниз. Бракоделов тут не любят.")
                }
            }
            return
        }
        if (from <= 0) return

        val text = rawText.replace(Regex("\\[(club|public|id)\\d+\\|[^\\]]*\\]"), "").trim()
        if (text.isEmpty()) return
        BotLog.add("← ${shortPeer(peer)}: ${text.take(80)}")

        val c = cfg
        val isCommand = text.startsWith("/") || text.startsWith("!")

        if (isCommand) {
            val body = text.drop(1).trim()
            val cmd = body.substringBefore(' ').norm()
            val args = body.substringAfter(' ', "").trim()
            if (handleBuiltIn(vk, peer, cmd, args)) return
            val rule = c.rules.firstOrNull { r ->
                r.enabled && r.type == RuleType.COMMAND && r.triggers.any { it.norm().trimStart('/', '!') == cmd }
            } ?: return
            respond(vk, peer, from, cmid, rule)
            return
        }

        if (isQuiet()) return

        val t = text.norm()
        for (r in c.rules) {
            if (!r.enabled || r.type != RuleType.KEYWORD || r.responses.isEmpty()) continue
            if (!r.triggers.any { matches(t, it) }) continue
            if (!passes(r, peer)) continue
            respond(vk, peer, from, cmid, r)
            return
        }
        for (r in c.rules) {
            if (!r.enabled || r.type != RuleType.RANDOM || r.responses.isEmpty()) continue
            if (!passes(r, peer)) continue
            respond(vk, peer, from, cmid, r)
            return
        }
    }

    private fun matches(normText: String, trigger: String): Boolean {
        if (trigger.startsWith("re:")) {
            return runCatching {
                Regex(trigger.removePrefix("re:"), RegexOption.IGNORE_CASE).containsMatchIn(normText)
            }.getOrDefault(false)
        }
        val tr = trigger.norm()
        return tr.isNotBlank() && normText.contains(tr)
    }

    private fun passes(r: Rule, peer: Long): Boolean {
        val key = "${r.id}|$peer"
        val now = System.currentTimeMillis()
        val last = cooldowns[key] ?: 0L
        if (r.cooldownSec > 0 && now - last < r.cooldownSec * 1000L) return false
        if (Random.nextInt(100) >= r.chance) return false
        cooldowns[key] = now
        return true
    }

    private suspend fun respond(vk: VkApi, peer: Long, from: Long, cmid: Int, r: Rule) {
        val name = vk.firstName(from)
        val text = fill(r.responses.random(), peer, name)
        val c = cfg
        val dMin = c.delayMinSec.coerceAtLeast(0)
        val dMax = c.delayMaxSec.coerceAtLeast(dMin)
        delay(Random.nextLong(dMin * 1000L, dMax * 1000L + 1))
        send(vk, peer, text, if (r.reply) cmid else null)
    }

    private suspend fun handleBuiltIn(vk: VkApi, peer: Long, cmd: String, args: String): Boolean {
        val w = cfg.weather
        when (cmd) {
            "погода", "weather" -> {
                val f = forecast() ?: return send(vk, peer, "Погода не загрузилась. Видимо, тоже бракодел.").let { true }
                send(vk, peer, Weather.todayReport(w.city, f))
            }
            "завтра" -> {
                val f = forecast() ?: return send(vk, peer, "Прогноз не загрузился.").let { true }
                send(vk, peer, Weather.tomorrowReport(w.city, f))
            }
            "выходные" -> {
                val f = forecast() ?: return send(vk, peer, "Прогноз не загрузился.").let { true }
                send(vk, peer, Weather.weekendReport(w.city, f))
            }
            "анекдот", "шутка" -> send(vk, peer, "😂 " + Jokes.random(cfg.jokes))
            "кто" -> {
                val who = members(peer).randomOrNull() ?: "никто"
                val what = args.ifBlank { "бракодел" }
                send(vk, peer, "🎯 Я тут посчитал… $what — это $who. Учтём.")
            }
            "помощь", "help", "команды" -> send(vk, peer, helpText())
            else -> return false
        }
        return true
    }

    private fun helpText(): String {
        val sb = StringBuilder("📋 Команды:\n")
        sb.append("/погода — погода сейчас и осадки\n")
        sb.append("/завтра — прогноз на завтра\n")
        sb.append("/выходные — прогноз на выходные\n")
        sb.append("/анекдот — анекдот\n")
        sb.append("/кто <текст> — кто сегодня <текст>\n")
        cfg.rules.filter { it.enabled && it.type == RuleType.COMMAND }.forEach { r ->
            sb.append("/${r.triggers.firstOrNull()?.trimStart('/', '!') ?: "?"} — ${r.name.substringAfter("— ", r.name)}\n")
        }
        return sb.toString().trim()
    }

    // ---------------- Расписание, анекдоты, погода ----------------

    private suspend fun scheduleLoop() {
        while (scope.isActive) {
            try {
                tickSchedule()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BotLog.add("⚠️ Расписание: ${e.message}")
            }
            delay(20_000)
        }
    }

    private suspend fun tickSchedule() {
        val vk = apiOrNull() ?: return
        val c = cfg
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        val dow = now.dayOfWeek.value
        val targets = targets()
        if (targets.isEmpty()) return

        for (s in c.schedules) {
            if (!s.enabled || dow !in s.days) continue
            val at = today.atTime(s.hour, s.minute)
            if (now.isBefore(at) || now.isAfter(at.plusMinutes(3))) continue
            if (scheduleFired[s.id] == today) continue
            scheduleFired[s.id] = today
            BotLog.add("⏰ ${s.name}")
            for (peer in targets) {
                val text = when (s.kind) {
                    ScheduleKind.TEXT -> s.responses.randomOrNull()?.let { fill(it, peer, "") }
                    ScheduleKind.JOKE -> "😂 " + Jokes.random(c.jokes)
                    ScheduleKind.WEATHER -> forecast()?.let { f ->
                        val head = s.responses.randomOrNull()?.let { fill(it, peer, "") + "\n" } ?: ""
                        head + Weather.todayReport(c.weather.city, f)
                    }
                }
                if (text != null) send(vk, peer, text)
            }
        }

        // Анекдоты по интервалу
        val j = c.jokes
        if (j.enabled && now.hour >= j.fromHour && now.hour < j.toHour && !isQuiet() && now.isAfter(nextJokeAt)) {
            nextJokeAt = now.plusHours(j.intervalHours.coerceAtLeast(1).toLong())
                .plusMinutes(Random.nextLong(-30, 31))
            val joke = Jokes.random(j)
            for (peer in targets) send(vk, peer, "😂 Анекдот по расписанию:\n$joke")
        }

        // Предупреждение об осадках
        val w = c.weather
        if (w.enabled && w.alertEnabled && now.isAfter(nextWeatherCheck)) {
            nextWeatherCheck = now.plusMinutes(w.checkMinutes.coerceAtLeast(10).toLong())
            val f = forecast(fresh = true)
            if (f != null && !isQuiet()) {
                val a = Weather.findAlert(f, w)
                if (a != null && a.start.isAfter(alertSuppressedUntil)) {
                    alertSuppressedUntil = a.end
                    val pool = if (a.snow) w.snowTemplates else w.rainTemplates
                    val tpl = pool.randomOrNull() ?: "Осадки {when}: {prob}%, ~{mm} мм"
                    BotLog.add("☔ Алерт: осадки с ${a.start.hour}:00, ${a.prob}%")
                    for (peer in targets) send(vk, peer, fill(Weather.alertText(a, w, tpl), peer, ""))
                }
            }
        }
    }

    private suspend fun forecast(fresh: Boolean = false): Weather.Forecast? {
        val maxAge = if (fresh) 5 * 60_000L else 15 * 60_000L
        forecastCache?.let { (t, f) -> if (System.currentTimeMillis() - t < maxAge) return f }
        val w = cfg.weather
        return runCatching { Weather.load(w.lat, w.lon) }
            .onFailure { BotLog.add("⚠️ Погода: ${it.message}") }
            .getOrNull()
            ?.also { forecastCache = System.currentTimeMillis() to it }
    }

    // ---------------- Утилиты ----------------

    private fun targets(): List<Long> {
        val c = cfg
        return c.targetPeers.ifEmpty { c.knownPeers.filter { it > 2_000_000_000L } }
    }

    private fun rememberPeer(peer: Long) {
        if (peer !in cfg.knownPeers) {
            ConfigStore.update { it.copy(knownPeers = it.knownPeers + peer) }
            BotLog.add("➕ Новая беседа: $peer")
        }
    }

    private fun isQuiet(): Boolean {
        val c = cfg
        val h = LocalDateTime.now().hour
        if (c.quietFrom == c.quietTo) return false
        return if (c.quietFrom > c.quietTo) h >= c.quietFrom || h < c.quietTo
        else h >= c.quietFrom && h < c.quietTo
    }

    private suspend fun members(peer: Long): List<String> {
        val list = cfg.members
        if (list.isNotEmpty()) return list
        return apiOrNull()?.chatMemberNames(peer) ?: emptyList()
    }

    private fun randomKef(sign: Int): String {
        val v = listOf(0.05, 0.1, 0.1, 0.15, 0.2, 0.25, 0.3, 0.5).random()
        val s = if (sign == 0) (if (Random.nextBoolean()) 1 else -1) else sign
        val num = String.format(Locale("ru"), "%.2f", v).trimEnd('0').trimEnd(',')
        return (if (s > 0) "+" else "-") + num + " кэф"
    }

    suspend fun fill(template: String, peer: Long, name: String): String {
        var out = template.replace("\\n", "\n")
        if (out.contains("{member")) {
            val m = members(peer).shuffled()
            out = out.replace("{member2}", m.getOrNull(1) ?: m.firstOrNull() ?: "кто-то")
                .replace("{member}", m.firstOrNull() ?: "кто-то")
        }
        out = out.replace("{name}", name.ifBlank { "народ" })
            .replace("{city}", cfg.weather.city)
        while (out.contains("{kef}")) out = out.replaceFirst("{kef}", randomKef(0))
        while (out.contains("{pluskef}")) out = out.replaceFirst("{pluskef}", randomKef(1))
        while (out.contains("{minuskef}")) out = out.replaceFirst("{minuskef}", randomKef(-1))
        if (out.contains("{weather}")) {
            out = out.replace("{weather}", forecast()?.let { Weather.nowLine(it) } ?: "погода неизвестна")
        }
        return out
    }

    private suspend fun send(vk: VkApi, peer: Long, text: String, replyTo: Int? = null) {
        try {
            vk.send(peer, text, replyTo)
            BotLog.sent.value = BotLog.sent.value + 1
            BotLog.add("→ ${shortPeer(peer)}: ${text.take(80).replace('\n', ' ')}")
        } catch (e: VkException) {
            if (replyTo != null && e.code == 100) {
                // цитата не поддерживается — шлём без неё
                runCatching { vk.send(peer, text, null) }
                    .onSuccess { BotLog.sent.value = BotLog.sent.value + 1 }
                return
            }
            BotLog.add("❌ Не отправилось (${e.code}): ${e.message}")
            if (e.code == 917 || e.code == 7) {
                BotLog.add("ℹ️ Похоже, у бота нет доступа к беседе — дай ему права администратора")
            }
        } catch (e: Exception) {
            BotLog.add("❌ Не отправилось: ${e.message}")
        }
    }

    private fun shortPeer(peer: Long) =
        if (peer > 2_000_000_000L) "беседа ${peer - 2_000_000_000L}" else "лс $peer"

    /** Тестовое сообщение из приложения. */
    suspend fun sendTest(text: String): String {
        val vk = apiOrNull() ?: return "Нет токена или ID группы"
        val t = targets()
        if (t.isEmpty()) return "Бот ещё не знает ни одной беседы — напиши что-нибудь в беседе после добавления бота"
        for (p in t) send(vk, p, fill(text, p, ""))
        return "Отправлено в ${t.size} бесед(у)"
    }

    suspend fun weatherPreview(): String {
        val f = forecast(fresh = true) ?: return "Погода не загрузилась — проверь интернет"
        val w = cfg.weather
        val alert = Weather.findAlert(f, w)
        return Weather.todayReport(w.city, f) + "\n\n" +
            (if (alert != null) "Алерт сработал бы: осадки с ${alert.start.hour}:00, ${alert.prob}%"
            else "Алерт сейчас не сработал бы (осадков выше порога в ближайшие ${w.lookAheadHours} ч нет)")
    }
}
