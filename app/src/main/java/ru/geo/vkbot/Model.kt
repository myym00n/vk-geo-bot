package ru.geo.vkbot

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Тип реакции на сообщение. */
enum class RuleType(val title: String) {
    KEYWORD("Слово в сообщении"),
    COMMAND("Команда (/...)"),
    RANDOM("Случайный ответ на любое сообщение");
}

/** Что делает запись расписания. */
enum class ScheduleKind(val title: String) {
    TEXT("Случайная фраза"),
    WEATHER("Сводка погоды"),
    JOKE("Анекдот");
}

data class Rule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val enabled: Boolean = true,
    val type: RuleType = RuleType.KEYWORD,
    /** Слова/фразы (без учёта регистра, ё=е). Строка с префиксом re: — регулярка. */
    val triggers: List<String> = emptyList(),
    /** Варианты ответа, бот берёт случайный. */
    val responses: List<String> = emptyList(),
    /** Шанс срабатывания, %. */
    val chance: Int = 100,
    /** Не срабатывать чаще, чем раз в N секунд (в одной беседе). */
    val cooldownSec: Int = 0,
    /** Отвечать «ответом» на сообщение (с цитатой). */
    val reply: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("enabled", enabled).put("type", type.name)
        .put("triggers", JSONArray(triggers)).put("responses", JSONArray(responses))
        .put("chance", chance).put("cooldownSec", cooldownSec).put("reply", reply)

    companion object {
        fun fromJson(o: JSONObject) = Rule(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = o.optString("name"),
            enabled = o.optBoolean("enabled", true),
            type = runCatching { RuleType.valueOf(o.optString("type")) }.getOrDefault(RuleType.KEYWORD),
            triggers = o.optJSONArray("triggers").toStringList(),
            responses = o.optJSONArray("responses").toStringList(),
            chance = o.optInt("chance", 100),
            cooldownSec = o.optInt("cooldownSec", 0),
            reply = o.optBoolean("reply", true),
        )
    }
}

data class Schedule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val enabled: Boolean = true,
    val kind: ScheduleKind = ScheduleKind.TEXT,
    val hour: Int = 9,
    val minute: Int = 0,
    /** Дни недели 1=Пн … 7=Вс. */
    val days: Set<Int> = setOf(1, 2, 3, 4, 5),
    val responses: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("enabled", enabled).put("kind", kind.name)
        .put("hour", hour).put("minute", minute).put("days", JSONArray(days.sorted()))
        .put("responses", JSONArray(responses))

    companion object {
        fun fromJson(o: JSONObject): Schedule {
            val d = o.optJSONArray("days")
            val days = mutableSetOf<Int>()
            if (d != null) for (i in 0 until d.length()) days += d.optInt(i)
            return Schedule(
                id = o.optString("id", UUID.randomUUID().toString()),
                name = o.optString("name"),
                enabled = o.optBoolean("enabled", true),
                kind = runCatching { ScheduleKind.valueOf(o.optString("kind")) }.getOrDefault(ScheduleKind.TEXT),
                hour = o.optInt("hour", 9),
                minute = o.optInt("minute", 0),
                days = days,
                responses = o.optJSONArray("responses").toStringList(),
            )
        }
    }
}

data class WeatherSettings(
    val enabled: Boolean = true,
    val city: String = "Нижний Новгород",
    val lat: Double = 56.3269,
    val lon: Double = 44.0059,
    /** Предупреждать об осадках заранее. */
    val alertEnabled: Boolean = true,
    /** Порог вероятности осадков, %. */
    val threshold: Int = 60,
    /** Минимум осадков за час, мм. */
    val minMm: Double = 0.3,
    /** Насколько часов вперёд смотреть. */
    val lookAheadHours: Int = 2,
    /** Как часто проверять, минут. */
    val checkMinutes: Int = 30,
    val rainTemplates: List<String> = emptyList(),
    val snowTemplates: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled).put("city", city).put("lat", lat).put("lon", lon)
        .put("alertEnabled", alertEnabled).put("threshold", threshold).put("minMm", minMm)
        .put("lookAheadHours", lookAheadHours).put("checkMinutes", checkMinutes)
        .put("rainTemplates", JSONArray(rainTemplates)).put("snowTemplates", JSONArray(snowTemplates))

    companion object {
        fun fromJson(o: JSONObject?, def: WeatherSettings): WeatherSettings {
            if (o == null) return def
            return WeatherSettings(
                enabled = o.optBoolean("enabled", def.enabled),
                city = o.optString("city", def.city),
                lat = o.optDouble("lat", def.lat),
                lon = o.optDouble("lon", def.lon),
                alertEnabled = o.optBoolean("alertEnabled", def.alertEnabled),
                threshold = o.optInt("threshold", def.threshold),
                minMm = o.optDouble("minMm", def.minMm),
                lookAheadHours = o.optInt("lookAheadHours", def.lookAheadHours),
                checkMinutes = o.optInt("checkMinutes", def.checkMinutes),
                rainTemplates = o.optJSONArray("rainTemplates").toStringList().ifEmpty { def.rainTemplates },
                snowTemplates = o.optJSONArray("snowTemplates").toStringList().ifEmpty { def.snowTemplates },
            )
        }
    }
}

data class JokeSettings(
    val enabled: Boolean = true,
    /** Раз в сколько часов кидать анекдот. */
    val intervalHours: Int = 3,
    val fromHour: Int = 10,
    val toHour: Int = 20,
    /** Анекдоты 18+. */
    val adult: Boolean = true,
    /** Свои анекдоты (используются, если сайт недоступен, и вперемешку). */
    val ownJokes: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled).put("intervalHours", intervalHours)
        .put("fromHour", fromHour).put("toHour", toHour).put("adult", adult)
        .put("ownJokes", JSONArray(ownJokes))

    companion object {
        fun fromJson(o: JSONObject?, def: JokeSettings): JokeSettings {
            if (o == null) return def
            return JokeSettings(
                enabled = o.optBoolean("enabled", def.enabled),
                intervalHours = o.optInt("intervalHours", def.intervalHours),
                fromHour = o.optInt("fromHour", def.fromHour),
                toHour = o.optInt("toHour", def.toHour),
                adult = o.optBoolean("adult", def.adult),
                ownJokes = o.optJSONArray("ownJokes").toStringList().ifEmpty { def.ownJokes },
            )
        }
    }
}

data class BotConfig(
    val token: String = "",
    val groupId: Long = 0,
    /** Должен ли бот работать (переживает перезагрузку). */
    val enabled: Boolean = false,
    /** Беседы, куда слать расписание/погоду/анекдоты (peer_id). Пусто — во все известные. */
    val targetPeers: List<Long> = emptyList(),
    /** Беседы, из которых бот уже получал сообщения. */
    val knownPeers: List<Long> = emptyList(),
    val quietFrom: Int = 23,
    val quietTo: Int = 7,
    val delayMinSec: Int = 1,
    val delayMaxSec: Int = 4,
    /** Игнорировать паузы между срабатываниями правил. */
    val noCooldowns: Boolean = true,
    /** Игры включены. */
    val gamesEnabled: Boolean = true,
    /** Начислять реальный кэф за срабатывания правил. */
    val ruleKefCounts: Boolean = true,
    /** Список участников для {member}, «Брута дня» и т.п. */
    val members: List<String> = emptyList(),
    val greeting: List<String> = emptyList(),
    val rules: List<Rule> = emptyList(),
    val schedules: List<Schedule> = emptyList(),
    val weather: WeatherSettings = WeatherSettings(),
    val jokes: JokeSettings = JokeSettings(),
    val ai: AiSettings = AiSettings(),
    val persona: PersonaSettings = PersonaSettings(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("token", token).put("groupId", groupId).put("enabled", enabled)
        .put("targetPeers", JSONArray(targetPeers)).put("knownPeers", JSONArray(knownPeers))
        .put("quietFrom", quietFrom).put("quietTo", quietTo)
        .put("delayMinSec", delayMinSec).put("delayMaxSec", delayMaxSec)
        .put("noCooldowns", noCooldowns).put("gamesEnabled", gamesEnabled).put("ruleKefCounts", ruleKefCounts)
        .put("members", JSONArray(members)).put("greeting", JSONArray(greeting))
        .put("rules", JSONArray().apply { rules.forEach { put(it.toJson()) } })
        .put("schedules", JSONArray().apply { schedules.forEach { put(it.toJson()) } })
        .put("weather", weather.toJson())
        .put("jokes", jokes.toJson())
        .put("ai", ai.toJson())
        .put("persona", persona.toJson())

    /** Конфиг без токена — для экспорта правил «поделиться». */
    fun withoutSecrets() = copy(token = "", enabled = false, ai = ai.withoutSecrets())

    companion object {
        fun fromJson(o: JSONObject, def: BotConfig): BotConfig {
            val rulesArr = o.optJSONArray("rules")
            val schArr = o.optJSONArray("schedules")
            return BotConfig(
                token = o.optString("token", def.token),
                groupId = o.optLong("groupId", def.groupId),
                enabled = o.optBoolean("enabled", false),
                targetPeers = o.optJSONArray("targetPeers").toLongList(),
                knownPeers = o.optJSONArray("knownPeers").toLongList(),
                quietFrom = o.optInt("quietFrom", def.quietFrom),
                quietTo = o.optInt("quietTo", def.quietTo),
                delayMinSec = o.optInt("delayMinSec", def.delayMinSec),
                delayMaxSec = o.optInt("delayMaxSec", def.delayMaxSec),
                noCooldowns = o.optBoolean("noCooldowns", def.noCooldowns),
                gamesEnabled = o.optBoolean("gamesEnabled", def.gamesEnabled),
                ruleKefCounts = o.optBoolean("ruleKefCounts", def.ruleKefCounts),
                members = if (o.has("members")) o.optJSONArray("members").toStringList() else def.members,
                greeting = if (o.has("greeting")) o.optJSONArray("greeting").toStringList() else def.greeting,
                rules = if (rulesArr != null) List(rulesArr.length()) { Rule.fromJson(rulesArr.getJSONObject(it)) } else def.rules,
                schedules = if (schArr != null) List(schArr.length()) { Schedule.fromJson(schArr.getJSONObject(it)) } else def.schedules,
                weather = WeatherSettings.fromJson(o.optJSONObject("weather"), def.weather),
                jokes = JokeSettings.fromJson(o.optJSONObject("jokes"), def.jokes),
                ai = AiSettings.fromJson(o.optJSONObject("ai")),
                persona = PersonaSettings.fromJson(o.optJSONObject("persona")),
            )
        }
    }
}

fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return List(length()) { optString(it) }.filter { it.isNotBlank() }
}

fun JSONArray?.toLongList(): List<Long> {
    if (this == null) return emptyList()
    return List(length()) { optLong(it) }.filter { it != 0L }
}

/** Нормализация для поиска: нижний регистр, ё→е. */
fun String.norm(): String = lowercase().replace('ё', 'е')
