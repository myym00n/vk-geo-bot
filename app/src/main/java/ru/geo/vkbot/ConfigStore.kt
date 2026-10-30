package ru.geo.vkbot

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Хранилище настроек: один JSON-файл во внутренней памяти приложения. */
object ConfigStore {
    private lateinit var file: File
    private val _config = MutableStateFlow(defaultConfig())
    val config: StateFlow<BotConfig> = _config.asStateFlow()
    private var loaded = false

    fun defaultConfig() = BotConfig(
        members = DefaultConfig.members,
        greeting = DefaultConfig.greeting,
        rules = DefaultConfig.rules,
        schedules = DefaultConfig.schedules,
        weather = WeatherSettings(
            rainTemplates = DefaultConfig.rainTemplates,
            snowTemplates = DefaultConfig.snowTemplates,
        ),
        jokes = JokeSettings(ownJokes = DefaultConfig.ownJokes),
    )

    @Synchronized
    fun init(context: Context) {
        if (loaded) return
        file = File(context.filesDir, "config.json")
        if (file.exists()) {
            runCatching {
                _config.value = BotConfig.fromJson(JSONObject(file.readText()), defaultConfig())
            }.onFailure { BotLog.add("⚠️ Не удалось прочитать настройки: ${it.message}") }
        }
        loaded = true
    }

    @Synchronized
    fun update(transform: (BotConfig) -> BotConfig) {
        val next = transform(_config.value)
        _config.value = next
        runCatching { file.writeText(next.toJson().toString(2)) }
            .onFailure { BotLog.add("⚠️ Не удалось сохранить настройки: ${it.message}") }
    }

    fun exportJson(includeToken: Boolean): String {
        val c = if (includeToken) _config.value else _config.value.withoutSecrets()
        return c.toJson().toString(2)
    }

    /** Импорт: токен и включённость сохраняются текущие, если в файле их нет. */
    fun importJson(text: String) {
        val o = JSONObject(text)
        update { cur ->
            val imported = BotConfig.fromJson(o, defaultConfig())
            imported.copy(
                token = imported.token.ifBlank { cur.token },
                groupId = if (imported.groupId == 0L) cur.groupId else imported.groupId,
                enabled = cur.enabled,
                knownPeers = (cur.knownPeers + imported.knownPeers).distinct(),
            )
        }
    }

    fun resetContent() = update { cur ->
        val d = defaultConfig()
        cur.copy(
            members = d.members, greeting = d.greeting, rules = d.rules,
            schedules = d.schedules,
            weather = cur.weather.copy(rainTemplates = d.weather.rainTemplates, snowTemplates = d.weather.snowTemplates),
            jokes = cur.jokes.copy(ownJokes = d.jokes.ownJokes),
        )
    }
}

/** Лог и статистика — в памяти, для экрана «Лог». */
object BotLog {
    private const val MAX = 300
    private val fmt = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault())
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    val status = MutableStateFlow("Остановлен")
    val sent = MutableStateFlow(0)
    val received = MutableStateFlow(0)

    @Synchronized
    fun add(msg: String) {
        val line = "${fmt.format(Date())}  $msg"
        _lines.value = (listOf(line) + _lines.value).take(MAX)
    }

    fun clear() {
        _lines.value = emptyList()
    }
}
