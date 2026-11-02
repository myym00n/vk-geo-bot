package ru.geo.vkbot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import kotlin.random.Random

/** Вся логика бота: приём сообщений, правила, расписание, погода, анекдоты. */
class BotEngine(private val scope: CoroutineScope) {

    private var api: VkApi? = null
    private var apiKey = ""

    private val cooldowns = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val games = Games(
        scope = scope,
        sendFn = { peer, text, exact -> apiOrNull()?.let { send(it, peer, text, exact = exact) } },
        members = { peer -> members(peer) },
    )
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

    @Synchronized
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

    /** Ждём паузу, но просыпаемся сразу, как только появилась сеть. */
    private suspend fun waitOrNetwork(ms: Long) {
        val gen = NetWatch.generation.value
        kotlinx.coroutines.withTimeoutOrNull(ms) { NetWatch.generation.first { it != gen } }
    }

    private suspend fun pollLoop() {
        var backoff = 5_000L
        var lastTs: String? = null
        var offlineSince = 0L
        var lastErr = ""
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
                // продолжаем с того места, где оборвались, — сообщения за время обрыва не теряются
                lastTs?.let { server.ts = it }
                BotLog.status.value = "✅ Работает: $name"
                if (offlineSince > 0) {
                    val mins = (System.currentTimeMillis() - offlineSince) / 60_000
                    BotLog.add("✅ Связь вернулась (не было ~$mins мин)")
                    offlineSince = 0L
                } else if (lastErr.isNotEmpty() || lastTs == null) {
                    BotLog.add("Подключился к Long Poll ($name)")
                }
                lastErr = ""
                backoff = 5_000L
                while (scope.isActive && apiOrNull() === vk) {
                    val r = vk.poll(server)
                    if (r.has("failed")) {
                        when (r.optInt("failed")) {
                            1 -> server.ts = r.optString("ts", server.ts)
                            2 -> { lastTs = server.ts; break } // ключ протух — новый ключ, тот же ts
                            else -> { lastTs = null; break }
                        }
                        continue
                    }
                    server.ts = r.optString("ts", server.ts)
                    lastTs = server.ts
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
                BotLog.status.value = "📡 Нет связи, жду сеть…"
                if (offlineSince == 0L) {
                    offlineSince = System.currentTimeMillis()
                    BotLog.add("📡 Пропала связь: ${e.javaClass.simpleName}. Переподключусь сам, как появится сеть")
                }
                lastErr = e.javaClass.simpleName
                waitOrNetwork(backoff)
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

        // Цель для игр: упомянутый пользователь или автор сообщения, на которое ответили
        val mention = Regex("\\[id(\\d+)\\|[^\\]]*\\]").find(rawText)?.groupValues?.get(1)?.toLongOrNull()
        val replyMsg = msg.optJSONObject("reply_message")
        val replyFrom = replyMsg?.optLong("from_id")?.takeIf { it > 0 }
        val targetId = mention ?: replyFrom
        val gid = cfg.groupId
        val addressedToBot = rawText.contains("[club$gid|") || rawText.contains("[public$gid|") ||
            replyMsg?.optLong("from_id") == -gid ||
            rawText.trimStart().norm().let { it.startsWith("бот,") || it.startsWith("бот ") }

        val text = rawText.replace(Regex("\\[(club|public|id)\\d+\\|[^\\]]*\\]"), "").trim().trimStart(',', ' ')
        if (text.isEmpty()) return
        val fromName = vk.firstName(from)
        BotLog.add("← ${shortPeer(peer)} $fromName: ${text.take(80)}")
        remember(peer, "$fromName: $text")

        val c = cfg
        if (c.gamesEnabled && games.checkAnswer(peer, from, fromName, text)) return

        val isCommand = text.startsWith("/") || text.startsWith("!")

        if (isCommand) {
            val body = text.drop(1).trim()
            val cmd = body.substringBefore(' ').norm().trimEnd { !it.isLetterOrDigit() }.substringBefore('@')
            val args = body.substringAfter(' ', "").trim()
            if (handleBuiltIn(vk, peer, cmd, args)) return
            if (handleAiCommand(vk, peer, cmd, args, from, fromName, targetId, replyMsg)) return
            if (c.gamesEnabled) {
                val tName = targetId?.let { vk.firstName(it) }
                val ctx = GameCtx(peer, from, fromName, targetId, tName, args)
                if (games.handle(cmd, ctx)) {
                    BotLog.add("🎮 /$cmd")
                    return
                }
            }
            val rule = c.rules.firstOrNull { r ->
                r.enabled && r.type == RuleType.COMMAND && r.triggers.any { it.norm().trimStart('/', '!') == cmd }
            }
            if (rule == null) {
                BotLog.add("· неизвестная команда /$cmd")
                send(vk, peer, "Нет такой команды, $fromName. Бракодел. Список: /помощь")
                return
            }
            respond(vk, peer, from, cmid, rule)
            return
        }

        // Обращение к боту — отвечает нейросеть (кроме «беру в работу» и т.п. — там шаблон быстрее и смешнее)
        val ai = c.ai
        if (addressedToBot && ai.ready && ai.chat) {
            val t0 = text.norm()
            val keywordHit = c.rules.any { r -> r.enabled && r.type == RuleType.KEYWORD && r.chance >= 100 && r.triggers.any { matches(t0, it) } }
            if (!keywordHit) {
                val answer = Ai.complete(ai, chatMessages(peer, fromName, text), maxTokens = 250)
                if (answer != null) {
                    BotLog.add("🧠 ответ нейросети")
                    applyKefFromText(peer, from, fromName, answer)
                    personaDone.add(peer); send(vk, peer, answer, cmid)
                    return
                }
                // нейросеть промолчала (цензура/ошибка) — всё равно отвечаем, чтобы не игнорить
                val fallback = c.persona.active?.phrase("any")
                    ?: c.rules.firstOrNull { it.enabled && it.type == RuleType.RANDOM }?.responses?.randomOrNull()
                if (fallback != null) {
                    BotLog.add("· нейросеть молчит — отвечаю фразой")
                    personaDone.add(peer)
                    send(vk, peer, fill(fallback, peer, fromName, from), cmid)
                    return
                }
            }
        }

        if (isQuiet()) {
            BotLog.add("· тихие часы, молчу")
            return
        }

        val t = text.norm()
        var matchedButSkipped: String? = null
        for (r in c.rules) {
            if (!r.enabled || r.type != RuleType.KEYWORD || r.responses.isEmpty()) continue
            if (!r.triggers.any { matches(t, it) }) continue
            if (!passes(r, peer)) {
                if (matchedButSkipped == null) matchedButSkipped = r.name
                continue
            }
            BotLog.add("✓ правило «${r.name}»")
            respond(vk, peer, from, cmid, r)
            return
        }
        if (matchedButSkipped != null) BotLog.add("· «$matchedButSkipped» подошло, но пропущено (шанс/пауза)")
        if (ai.ready && !Ai.busy && ai.smartChance > 0 && text.length >= 6 && Random.nextInt(100) < ai.smartChance) {
            val answer = Ai.ask(
                ai,
                "Прокомментируй последнее сообщение от $fromName одной короткой язвительной фразой в своём стиле. Можешь начислить или снять кэф.",
                context = "Последние сообщения беседы:\n" + recent(peer, 15),
                maxTokens = 120,
            )
            if (answer != null) {
                BotLog.add("🧠 умный подкол")
                applyKefFromText(peer, from, fromName, answer)
                personaDone.add(peer); send(vk, peer, answer, cmid)
                return
            }
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
        if (!cfg.noCooldowns && r.cooldownSec > 0 && now - last < r.cooldownSec * 1000L) return false
        if (Random.nextInt(100) >= r.chance) return false
        cooldowns[key] = now
        return true
    }

    private suspend fun respond(vk: VkApi, peer: Long, from: Long, cmid: Int, r: Rule) {
        val name = vk.firstName(from)
        val p = cfg.persona.active
        val own = p?.rules?.get(r.name)?.takeIf { it.isNotEmpty() }
            ?: if (p != null && r.type == RuleType.RANDOM) p.allPhrases.takeIf { it.isNotEmpty() } else null
        val text = if (own != null) {
            personaDone.add(peer)
            fill(own.random(), peer, name, from)
        } else {
            fill(r.responses.random(), peer, name, from)
        }
        val c = cfg
        if (p == null) {
            val dMin = c.delayMinSec.coerceAtLeast(0)
            val dMax = c.delayMaxSec.coerceAtLeast(dMin)
            delay(Random.nextLong(dMin * 1000L, dMax * 1000L + 1))
        }
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
            "анекдот", "шутка" -> send(vk, peer, "😂 " + joke())
            "кто" -> {
                val who = members(peer).randomOrNull() ?: "никто"
                val what = args.ifBlank { "бракодел" }
                send(vk, peer, "🎯 Я тут посчитал… $what — это $who. Учтём.")
            }
            "помощь", "help", "команды" -> send(vk, peer, helpText(), raw = true)
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
        if (cfg.gamesEnabled) sb.append("\n").append(games.helpText)
        if (cfg.ai.ready) {
            sb.append("\n\n🧠 Нейросеть:\n")
            sb.append("Тегни бота или ответь на его сообщение — поговорит с тобой\n")
            sb.append("/спроси <вопрос> — спросить что угодно\n")
            sb.append("/прожарка @кто — прожарить человека\n")
            sb.append("/оцени — ответом на сообщение: оценка и кэф\n")
            sb.append("/итоги — итоги дня в беседе")
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
            // окно 30 минут: если телефон «притормозил», сообщение всё равно уйдёт
            if (now.isBefore(at) || now.isAfter(at.plusMinutes(30))) continue
            if (scheduleFired[s.id] == today) continue
            scheduleFired[s.id] = today
            BotLog.add("⏰ ${s.name}")
            for (peer in targets) scope.launch {
              try {
                val text = when (s.kind) {
                    ScheduleKind.TEXT -> {
                        val own = c.persona.active?.schedules?.get(s.name)?.takeIf { it.isNotEmpty() }
                        if (own != null) {
                            personaDone.add(peer)
                            fill(own.random(), peer, "")
                        } else {
                            aiSchedulePhrase(s, peer)?.also { personaDone.add(peer) }
                                ?: s.responses.randomOrNull()?.let { fill(it, peer, "") }
                        }
                    }
                    ScheduleKind.JOKE -> "😂 " + joke()
                    ScheduleKind.WEATHER -> forecast()?.let { f ->
                        val head = s.responses.randomOrNull()?.let { fill(it, peer, "") + "\n" } ?: ""
                        head + Weather.todayReport(c.weather.city, f)
                    }
                }
                if (text != null) send(vk, peer, text)
                else BotLog.add("⚠️ «${s.name}» не отправлено (нет погоды/текста)")
              } catch (e: CancellationException) {
                throw e
              } catch (e: Exception) {
                BotLog.add("⚠️ «${s.name}»: ${e.message}")
              }
            }
        }

        // Анекдоты по интервалу
        val j = c.jokes
        if (j.enabled && now.hour >= j.fromHour && now.hour < j.toHour && !isQuiet() && now.isAfter(nextJokeAt)) {
            nextJokeAt = now.plusHours(j.intervalHours.coerceAtLeast(1).toLong())
                .plusMinutes(Random.nextLong(-30, 31))
            scope.launch {
                runCatching {
                    val joke = joke()
                    for (peer in targets) send(vk, peer, "😂 Анекдот по расписанию:\n$joke")
                }
            }
        }

        // Предупреждение об осадках
        val w = c.weather
        if (w.enabled && w.alertEnabled && !isQuiet() && NetWatch.online && now.isAfter(nextWeatherCheck)) {
            nextWeatherCheck = now.plusMinutes(w.checkMinutes.coerceAtLeast(10).toLong())
            scope.launch { runCatching { weatherAlert(vk, targets) } }
        }
    }

    private suspend fun weatherAlert(vk: VkApi, targets: List<Long>) {
        val w = cfg.weather
        run {
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

    private fun randomKefValue(sign: Int): Double {
        val v = listOf(0.05, 0.1, 0.1, 0.15, 0.2, 0.25, 0.3, 0.5).random()
        val s = if (sign == 0) (if (Random.nextBoolean()) 1 else -1) else sign
        return v * s
    }

    /**
     * Подставляет плейсхолдеры. Если указан uid и в шаблоне нет {member},
     * выданный кэф реально начисляется автору сообщения (для /баланс и /топ).
     */
    suspend fun fill(template: String, peer: Long, name: String, uid: Long = 0L): String {
        var out = template.replace("\\n", "\n")
        val credit = uid > 0 && !out.contains("{member") && cfg.ruleKefCounts
        if (out.contains("{member")) {
            val m = members(peer).shuffled()
            out = out.replace("{member2}", m.getOrNull(1) ?: m.firstOrNull() ?: "кто-то")
                .replace("{member}", m.firstOrNull() ?: "кто-то")
        }
        out = out.replace("{name}", name.ifBlank { "народ" })
            .replace("{city}", cfg.weather.city)
        var total = 0.0
        for ((ph, sign) in listOf("{kef}" to 0, "{pluskef}" to 1, "{minuskef}" to -1)) {
            while (out.contains(ph)) {
                val v = randomKefValue(sign)
                total += v
                out = out.replaceFirst(ph, Stats.fmtKef(v) + " кэф")
            }
        }
        if (credit && total != 0.0) {
            Stats.update(peer, uid, name) { it.kef += total }
        }
        if (out.contains("{weather}")) {
            out = out.replace("{weather}", forecast()?.let { Weather.nowLine(it) } ?: "погода неизвестна")
        }
        return out
    }

    /** Беседы, где текущее сообщение уже написано «голосом» персонажа — не переписываем повторно. */
    private val personaDone = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Long, Boolean>())

    private suspend fun send(vk: VkApi, peer: Long, rawText: String, replyTo: Int? = null, raw: Boolean = false, exact: Boolean = false) {
        var text = rawText
        val p = cfg.persona.active
        val done = personaDone.remove(peer)
        if (p != null && !raw) {
            if (!done && !exact && cfg.persona.rewrite && cfg.ai.ready) {
                Ai.restyle(cfg.ai, p, rawText, timeoutMs = 12_000)?.let { text = it }
            }
            // Как живой человек: без эмодзи и оформления, короткими сообщениями, с «печатает…»
            val keep = p.allPhrases.filter { ph -> ph.any { it.isLetter() } && ph.filter { it.isLetter() }.all { it.isUpperCase() } }
                .flatMap { it.lines() }.map { it.trim() }.toSet()
            // для точных сообщений (топ, баланс) имя не вырезаем — там «Артём: -1,2» это строка рейтинга
            val cleaned = Humanize.clean(text, if (exact) "" else p.name, keep)
            var parts = if (exact) listOf(cleaned) else Humanize.split(cleaned)
            if (!exact) {
                // не повторяем то, что уже писали недавно
                val said = recentBotLines(peer)
                parts = parts.map { part ->
                    part.lines().filter { l -> Humanize.key(l).let { k -> k.length < 4 || k !in said } }.joinToString("\n")
                }.filter { it.isNotBlank() }
                if (parts.isEmpty()) parts = listOfNotNull(p.phrase("any"))
            }
            if (parts.isEmpty()) return
            parts.forEachIndexed { i, part ->
                vk.typing(peer)
                delay((500L + part.length * 40L + Random.nextLong(0, 500)).coerceAtMost(3_500L))
                val quote = if (i == 0 && replyTo != null && Random.nextInt(100) < 25) replyTo else null
                sendOne(vk, peer, part, quote)
            }
            return
        }
        sendOne(vk, peer, text, replyTo)
    }

    private suspend fun sendOne(vk: VkApi, peer: Long, text: String, replyTo: Int?) {
        try {
            vk.send(peer, text, replyTo)
            remember(peer, "Бот: ${text.take(300)}")
            BotLog.sent.value = BotLog.sent.value + 1
            BotLog.add("→ ${shortPeer(peer)}: ${text.take(80).replace('\n', ' ')}")
        } catch (e: VkException) {
            if (replyTo != null) {
                // цитата не прошла — шлём без неё
                BotLog.add("ℹ️ Цитата не прошла (${e.code}: ${e.message}), отправляю без неё")
                runCatching { vk.send(peer, text, null) }
                    .onSuccess {
                        BotLog.sent.value = BotLog.sent.value + 1
                        BotLog.add("→ ${shortPeer(peer)}: ${text.take(80).replace('\n', ' ')}")
                    }
                    .onFailure { BotLog.add("❌ Не отправилось: ${it.message}") }
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


    // ---------------- Нейросеть ----------------

    private val recentLines = java.util.concurrent.ConcurrentHashMap<Long, ArrayDeque<String>>()
    private val dayLog = java.util.concurrent.ConcurrentHashMap<Long, Pair<LocalDate, MutableList<String>>>()

    private fun remember(peer: Long, line: String) {
        val q = recentLines.getOrPut(peer) { ArrayDeque() }
        synchronized(q) {
            q.addLast(line)
            while (q.size > 40) q.removeFirst()
        }
        val today = LocalDate.now()
        val cur = dayLog[peer]
        val log = if (cur == null || cur.first != today) mutableListOf<String>().also { dayLog[peer] = today to it } else cur.second
        synchronized(log) {
            if (log.size < 400) log.add(LocalDateTime.now().toLocalTime().withNano(0).toString().take(5) + " " + line.take(300))
        }
    }

    /** Нормализованные строки последних сообщений бота в беседе — чтобы не повторяться. */
    private fun recentBotLines(peer: Long): Set<String> {
        val q = recentLines[peer] ?: return emptySet()
        return synchronized(q) {
            q.toList().takeLast(40).filter { it.startsWith("Бот: ") }
                .flatMap { it.removePrefix("Бот: ").lines() }
                .map { Humanize.key(it) }.toSet()
        }
    }

    private fun recent(peer: Long, n: Int): String {
        val q = recentLines[peer] ?: return ""
        return synchronized(q) { q.toList().takeLast(n).joinToString("\n") }
    }

    /** Диалог: персона + последние сообщения беседы. */
    private fun chatMessages(peer: Long, fromName: String, text: String): List<Ai.Msg> {
        val base = cfg.persona.active?.systemPrompt(cfg.members)
            ?: (cfg.ai.persona + (if (cfg.members.isNotEmpty()) "\nУчастники беседы: ${cfg.members.joinToString(", ")}." else ""))
        val sys = base +
            "\nНиже последние сообщения беседы для контекста. Отвечай на последнее обращение к тебе."
        return listOf(
            Ai.Msg("system", sys),
            Ai.Msg(
                "user",
                "Беседа (строки «Бот:» — это твои прошлые сообщения):\n${recent(peer, 25)}\n\n" +
                    "Сейчас $fromName обращается к тебе: «$text». Ответь ему по-новому, не повторяй свои прошлые фразы.",
            ),
        )
    }

    /** Если нейросеть в ответе начислила «+0,2 кэф» — зачисляем в баланс. */
    private fun applyKefFromText(peer: Long, uid: Long, name: String, text: String) {
        if (!cfg.ruleKefCounts) return
        var total = 0.0
        Regex("([+\\-−–])\\s?(\\d+(?:[.,]\\d+)?)\\s*(?:к[эе]ф|коэф)", RegexOption.IGNORE_CASE).findAll(text).forEach { mr ->
            val v = mr.groupValues[2].replace(',', '.').toDoubleOrNull() ?: return@forEach
            if (v > 5) return@forEach
            total += if (mr.groupValues[1] == "+") v else -v
        }
        if (total != 0.0) Stats.update(peer, uid, name) { it.kef += total.coerceIn(-1.0, 1.0) }
    }

    private fun statsLine(peer: Long, id: Long): String {
        val p = Stats.get(peer, id) ?: return "статистики нет"
        return "кэф ${Stats.fmtKef(p.kef)}, дуэли ${p.wins}/${p.losses}, в рулетке умер ${p.deaths} раз из ${p.shots}, викторина ${p.quiz}"
    }

    private suspend fun handleAiCommand(
        vk: VkApi, peer: Long, cmd: String, args: String,
        from: Long, fromName: String, targetId: Long?, replyMsg: JSONObject?,
    ): Boolean {
        val aiCmds = setOf("спроси", "ии", "бот", "прожарка", "прожарь", "роаст", "итоги", "сводка", "оцени", "оценка")
        if (cmd !in aiCmds) return false
        val ai = cfg.ai
        if (!ai.ready) {
            send(vk, peer, "🧠 Нейросеть не подключена. Тимур, настрой во вкладке «ИИ».")
            return true
        }
        BotLog.add("🧠 /$cmd")
        when (cmd) {
            "спроси", "ии", "бот" -> {
                if (args.isBlank()) {
                    send(vk, peer, "Спроси что-нибудь, $fromName: /спроси почему я бракодел")
                    return true
                }
                val a = Ai.complete(ai, chatMessages(peer, fromName, args), maxTokens = 350)
                if (a != null) personaDone.add(peer)
                send(vk, peer, a ?: "Мозги перегрелись. Спроси позже. Учтём.")
                if (a != null) applyKefFromText(peer, from, fromName, a)
            }
            "прожарка", "прожарь", "роаст" -> {
                val id = targetId ?: from
                val name = if (targetId != null) vk.firstName(targetId) else fromName
                val a = Ai.ask(
                    ai,
                    if (cfg.persona.active != null)
                        "Подколи коллегу $name в одной-двух коротких фразах, спокойно, обычными буквами, как в своих примерах. Его статистика: ${statsLine(peer, id)}. Можно закончить «+ кэф» или «- кэф»."
                    else
                        "Устрой короткую жёсткую прожарку (3–4 предложения) коллеге по имени $name. Его статистика в беседе: ${statsLine(peer, id)}. " +
                            "Используй геодезический сленг, бракоделов, кэф. В конце вынеси вердикт с кэфом.",
                    context = "Последние сообщения беседы:\n" + recent(peer, 15),
                    maxTokens = 350,
                )
                if (a == null) {
                    send(vk, peer, "🔥 $name, я хотел тебя прожарить, но ты и так подгорел. -0,1 кэф.")
                    Stats.update(peer, id, name) { it.kef -= 0.1 }
                } else {
                    applyKefFromText(peer, id, name, a)
                    personaDone.add(peer)
                    send(vk, peer, "🔥 $a")
                }
            }
            "итоги", "сводка" -> {
                val log = dayLog[peer]?.takeIf { it.first == LocalDate.now() }?.second
                val lines = log?.let { synchronized(it) { it.toList() } }.orEmpty().filter { !it.contains(" Бот: ") }
                if (lines.size < 5) {
                    send(vk, peer, "📰 Итоги дня: никто ничего не делал. Как обычно. Всем -0 кэф.")
                    return true
                }
                val a = Ai.ask(
                    ai,
                    "Сделай язвительные итоги дня по этой переписке: 4–6 коротких пунктов (кто что взял в работу, кто ныл, кто бракоделил, кто молодец), каждый с новой строки и начинается с «•». В конце назови «героя дня» и «бракодела дня».",
                    context = "Переписка за сегодня:\n" + lines.takeLast(150).joinToString("\n"),
                    maxTokens = 500,
                )
                if (a != null) personaDone.add(peer)
                send(vk, peer, a?.let { "📰 ИТОГИ ДНЯ\n$it" } ?: "📰 Итоги не подвелись. Нейросеть тоже бракодел.")
            }
            "оцени", "оценка" -> {
                val target = replyMsg?.optString("text").orEmpty()
                val authorId = replyMsg?.optLong("from_id") ?: 0L
                if (target.isBlank() || authorId <= 0) {
                    send(vk, peer, "Ответь командой /оцени на чьё-нибудь сообщение — я его оценю и выдам кэф.")
                    return true
                }
                val author = vk.firstName(authorId)
                val a = Ai.ask(
                    ai,
                    "Оцени сообщение от $author: «${target.take(500)}».\n" +
                        "Ответь строго в две строки. Первая строка: «КЭФ: +0,2» — число от -0,5 до +0,5 со знаком. Вторая строка: короткий язвительный комментарий в твоём стиле.",
                    maxTokens = 150,
                )
                if (a == null) {
                    send(vk, peer, "Без комментариев. Но учтём.")
                    return true
                }
                val m = Regex("КЭФ:?\\s*([+\\-−–]?)\\s?(\\d+(?:[.,]\\d+)?)", RegexOption.IGNORE_CASE).find(a)
                var delta = 0.0
                if (m != null) {
                    val v = (m.groupValues[2].replace(',', '.').toDoubleOrNull() ?: 0.0).coerceIn(0.0, 0.5)
                    delta = if (m.groupValues[1] == "+" || m.groupValues[1].isEmpty()) v else -v
                }
                val comment = a.lines().filter { !it.contains("КЭФ", ignoreCase = true) }.joinToString(" ").trim()
                    .ifBlank { "Учтём." }
                if (delta != 0.0) Stats.update(peer, authorId, author) { it.kef += delta }
                personaDone.add(peer)
                send(vk, peer, "⚖️ $author: ${Stats.fmtKef(delta)} кэф\n$comment")
            }
        }
        return true
    }

    /** Анекдот: часть пишет нейросеть, остальное — сайт и свои. */
    private suspend fun joke(): String {
        val ai = cfg.ai
        if (ai.ready && ai.jokes && Random.nextBoolean()) {
            val topic = listOf(
                "геодезиста и заказчика", "бракодела", "тахеометр", "ГНСС и фикс", "полевых и офисных",
                "кэф к премии", "колодцы", "начальника отдела изысканий", "рейку и реечника", "Брута",
            ).random()
            val a = Ai.ask(ai, "Расскажи один короткий смешной анекдот (до 6 строк) про $topic. Только сам анекдот, без вступления.", maxTokens = 300)
            if (a != null) return a
        }
        return Jokes.random(cfg.jokes)
    }

    /** Фраза по расписанию от нейросети в духе заданных примеров. */
    private suspend fun aiSchedulePhrase(s: Schedule, peer: Long): String? {
        val ai = cfg.ai
        if (!ai.ready || !ai.schedules || s.responses.isEmpty()) return null
        val day = listOf("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")[LocalDate.now().dayOfWeek.value - 1]
        val weather = forecast()?.let { Weather.nowLine(it) } ?: ""
        val examples = s.responses.shuffled().take(4).joinToString("\n") { "— $it" }
        val a = Ai.ask(
            ai,
            "Напиши ОДНО новое сообщение для беседы в духе этих примеров (тема: «${s.name}»), но другими словами. Сегодня $day, время ${"%02d:%02d".format(s.hour, s.minute)}" +
                (if (weather.isNotBlank()) ", погода: $weather" else "") + ". Только текст сообщения.\nПримеры:\n$examples",
            maxTokens = 150,
        ) ?: return null
        return fill(a, peer, "")
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
