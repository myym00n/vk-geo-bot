package ru.geo.vkbot

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Профиль «персонажа»: бот говорит в стиле конкретного человека.
 * Формат файла (JSON):
 * {
 *   "name": "Денис",
 *   "style": "описание манеры письма",
 *   "examples": ["реальные сообщения…"],
 *   "phrases": { "agree": [...], "refuse": [...], "work": [...], "angry": [...],
 *                "laugh": [...], "greeting": [...], "any": [...] },
 *   "rules": { "Беру в работу": ["ответы…"], "Кэф": [...] },        // по названию правила
 *   "schedules": { "Утренний подъём": [...], "Пора домой": [...] }  // по названию расписания
 * }
 */
data class PersonaProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val style: String = "",
    val examples: List<String> = emptyList(),
    val phrases: Map<String, List<String>> = emptyMap(),
    val rules: Map<String, List<String>> = emptyMap(),
    val schedules: Map<String, List<String>> = emptyMap(),
) {
    val allPhrases: List<String> get() = phrases.values.flatten().distinct()

    fun phrase(category: String): String? =
        (phrases[category]?.takeIf { it.isNotEmpty() } ?: phrases["any"]?.takeIf { it.isNotEmpty() } ?: allPhrases.takeIf { it.isNotEmpty() })
            ?.random()

    /** Системный промпт нейросети: говорить как этот человек. */
    fun systemPrompt(members: List<String>): String {
        val ex = examples.shuffled().take(45).joinToString("\n") { "— " + it.replace('\n', ' ').take(220) }
        return buildString {
            append("Ты пишешь в рабочей беседе ВКонтакте геодезистов из Нижнего Новгорода от лица участника по имени $name. ")
            append("Все знают, что это бот-пародия на него, это дружеская шутка. Пиши ТОЧНО в его манере: та же длина сообщений, ")
            append("тот же регистр, пунктуация, скобочки, смайлы, сленг, словечки и уровень мата. Не будь вежливее, грамотнее или длиннее, чем он.\n")
            if (style.isNotBlank()) append("Его стиль: $style\n")
            if (members.isNotEmpty()) append("Участники беседы: ${members.joinToString(", ")}.\n")
            append("Общий сленг беседы: «бракодел», «учтём», «+кэф»/«-кэф» (коэффициент к премии), «Брут», «взял в работу».\n")
            append("Не говори, что ты ИИ или бот. Без markdown. Не шути про национальность, религию, болезни и внешность.\n")
            if (ex.isNotBlank()) append("Реальные сообщения $name для образца стиля:\n$ex")
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("style", style)
        .put("examples", JSONArray(examples))
        .put("phrases", mapToJson(phrases))
        .put("rules", mapToJson(rules))
        .put("schedules", mapToJson(schedules))

    companion object {
        private fun mapToJson(m: Map<String, List<String>>) = JSONObject().apply { m.forEach { (k, v) -> put(k, JSONArray(v)) } }

        private fun jsonToMap(o: JSONObject?): Map<String, List<String>> {
            if (o == null) return emptyMap()
            val r = linkedMapOf<String, List<String>>()
            o.keys().forEach { k -> r[k] = o.optJSONArray(k).toStringList() }
            return r
        }

        fun fromJson(o: JSONObject): PersonaProfile = PersonaProfile(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = o.optString("name").ifBlank { "Персонаж" },
            style = o.optString("style"),
            examples = o.optJSONArray("examples").toStringList(),
            phrases = jsonToMap(o.optJSONObject("phrases")),
            rules = jsonToMap(o.optJSONObject("rules")),
            schedules = jsonToMap(o.optJSONObject("schedules")),
        )
    }
}

data class PersonaSettings(
    val profiles: List<PersonaProfile> = emptyList(),
    /** id включённого профиля; null — режим выключен. */
    val activeId: String? = null,
    /** Переписывать нейросетью ответы команд, игр, погоды и расписания в его стиле. */
    val rewrite: Boolean = true,
) {
    val active: PersonaProfile? get() = activeId?.let { id -> profiles.firstOrNull { it.id == id } }

    fun toJson(): JSONObject = JSONObject()
        .put("profiles", JSONArray().apply { profiles.forEach { put(it.toJson()) } })
        .put("activeId", activeId ?: JSONObject.NULL)
        .put("rewrite", rewrite)

    companion object {
        fun fromJson(o: JSONObject?): PersonaSettings {
            if (o == null) return PersonaSettings()
            val arr = o.optJSONArray("profiles")
            val list = if (arr == null) emptyList() else List(arr.length()) { PersonaProfile.fromJson(arr.getJSONObject(it)) }
            val active = if (o.isNull("activeId")) null else o.optString("activeId").ifBlank { null }
            return PersonaSettings(list, active?.takeIf { id -> list.any { it.id == id } }, o.optBoolean("rewrite", true))
        }
    }
}
