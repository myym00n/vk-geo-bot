package ru.geo.vkbot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Погода через Open-Meteo (бесплатно, без ключа). */
object Weather {

    data class Hour(
        val time: LocalDateTime,
        val temp: Double,
        val prob: Int,
        val precip: Double,
        val snow: Double,
        val code: Int,
    ) {
        val isSnow get() = snow > 0.0 || code in 71..77 || code in 85..86
    }

    data class Day(
        val date: LocalDate,
        val tMax: Double,
        val tMin: Double,
        val precipSum: Double,
        val probMax: Int,
        val code: Int,
    )

    data class Forecast(
        val nowTemp: Double,
        val nowFeels: Double,
        val nowCode: Int,
        val nowPrecip: Double,
        val nowWind: Double,
        val hours: List<Hour>,
        val days: List<Day>,
    )

    suspend fun load(lat: Double, lon: Double): Forecast = withContext(Dispatchers.IO) {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m" +
            "&hourly=temperature_2m,precipitation_probability,precipitation,snowfall,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max" +
            "&timezone=Europe%2FMoscow&forecast_days=4&wind_speed_unit=ms"
        val o = JSONObject(Http.get(url))
        val cur = o.getJSONObject("current")
        val h = o.getJSONObject("hourly")
        val ht = h.getJSONArray("time")
        val hours = List(ht.length()) { i ->
            Hour(
                time = LocalDateTime.parse(ht.getString(i)),
                temp = h.getJSONArray("temperature_2m").optDouble(i, 0.0),
                prob = h.getJSONArray("precipitation_probability").optInt(i, 0),
                precip = h.getJSONArray("precipitation").optDouble(i, 0.0),
                snow = h.getJSONArray("snowfall").optDouble(i, 0.0),
                code = h.getJSONArray("weather_code").optInt(i, 0),
            )
        }
        val d = o.getJSONObject("daily")
        val dt = d.getJSONArray("time")
        val days = List(dt.length()) { i ->
            Day(
                date = LocalDate.parse(dt.getString(i)),
                tMax = d.getJSONArray("temperature_2m_max").optDouble(i, 0.0),
                tMin = d.getJSONArray("temperature_2m_min").optDouble(i, 0.0),
                precipSum = d.getJSONArray("precipitation_sum").optDouble(i, 0.0),
                probMax = d.getJSONArray("precipitation_probability_max").optInt(i, 0),
                code = d.getJSONArray("weather_code").optInt(i, 0),
            )
        }
        Forecast(
            nowTemp = cur.optDouble("temperature_2m", 0.0),
            nowFeels = cur.optDouble("apparent_temperature", 0.0),
            nowCode = cur.optInt("weather_code", 0),
            nowPrecip = cur.optDouble("precipitation", 0.0),
            nowWind = cur.optDouble("wind_speed_10m", 0.0),
            hours = hours,
            days = days,
        )
    }

    fun describe(code: Int): String = when (code) {
        0 -> "ясно ☀️"
        1 -> "в основном ясно 🌤"
        2 -> "переменная облачность ⛅"
        3 -> "пасмурно ☁️"
        45, 48 -> "туман 🌫"
        51, 53, 55 -> "морось 🌦"
        56, 57 -> "ледяная морось 🧊"
        61 -> "небольшой дождь 🌧"
        63 -> "дождь 🌧"
        65 -> "сильный дождь 🌧🌧"
        66, 67 -> "ледяной дождь 🧊🌧"
        71 -> "небольшой снег 🌨"
        73 -> "снег 🌨"
        75 -> "сильный снег ❄️❄️"
        77 -> "снежная крупа 🌨"
        80, 81 -> "ливень 🌦"
        82 -> "сильный ливень ⛈"
        85, 86 -> "снегопад ❄️"
        95 -> "гроза ⛈"
        96, 99 -> "гроза с градом ⛈"
        else -> "непонятно что"
    }

    private fun t(v: Double): String {
        val r = v.roundToInt()
        return if (r > 0) "+$r°" else "$r°"
    }

    private fun mm(v: Double) = String.format(Locale("ru"), "%.1f", v)

    /** Короткая строка «сейчас». */
    fun nowLine(f: Forecast): String =
        "${t(f.nowTemp)} (ощущается ${t(f.nowFeels)}), ${describe(f.nowCode)}, ветер ${f.nowWind.roundToInt()} м/с"

    /** Сводка: сейчас + ближайшие часы + сегодня. */
    fun todayReport(city: String, f: Forecast): String {
        val now = LocalDateTime.now()
        val sb = StringBuilder()
        sb.append("📍 $city\n")
        sb.append("Сейчас: ${nowLine(f)}\n")
        val next = f.hours.filter { it.time.isAfter(now.minusHours(1)) }.take(12)
        val wet = next.filter { it.prob >= 40 && it.precip >= 0.1 }
        if (wet.isEmpty()) {
            sb.append("Осадки в ближайшие 12 часов: не ожидаются 👍\n")
        } else {
            val first = wet.first()
            val total = wet.sumOf { it.precip }
            val kind = if (wet.any { it.isSnow }) "снег" else "дождь"
            sb.append("Осадки: $kind с ${first.time.hour}:00, вероятность до ${wet.maxOf { it.prob }}%, ~${mm(total)} мм\n")
            sb.append("По часам: ")
            sb.append(wet.take(8).joinToString(", ") { "${it.time.hour}ч ${it.prob}%" })
            sb.append("\n")
        }
        f.days.firstOrNull()?.let { d ->
            sb.append("За день: ${t(d.tMin)}…${t(d.tMax)}, осадков ${mm(d.precipSum)} мм (вероятность ${d.probMax}%)")
        }
        return sb.toString().trim()
    }

    fun dayReport(city: String, d: Day, title: String): String =
        "📍 $city, $title (${d.date.format(DateTimeFormatter.ofPattern("dd.MM"))}): " +
            "${t(d.tMin)}…${t(d.tMax)}, ${describe(d.code)}, осадки ${mm(d.precipSum)} мм, вероятность ${d.probMax}%"

    fun tomorrowReport(city: String, f: Forecast): String {
        val d = f.days.getOrNull(1) ?: return "Прогноз на завтра не загрузился"
        val tomorrow = d.date
        val hours = f.hours.filter { it.time.toLocalDate() == tomorrow && it.time.hour in 7..20 }
        val wet = hours.filter { it.prob >= 40 && it.precip >= 0.1 }
        val extra = if (wet.isEmpty()) "\nВ рабочее время сухо 👍"
        else "\nОсадки в рабочее время: " + wet.joinToString(", ") { "${it.time.hour}ч ${it.prob}%" }
        return dayReport(city, d, "завтра") + extra
    }

    fun weekendReport(city: String, f: Forecast): String {
        val list = f.days.filter { it.date.dayOfWeek.value >= 6 }
        if (list.isEmpty()) return "До выходных прогноз пока не дотягивается (видно на 4 дня вперёд)."
        return list.joinToString("\n") {
            dayReport(city, it, if (it.date.dayOfWeek.value == 6) "суббота" else "воскресенье")
        }
    }

    data class Alert(val start: LocalDateTime, val end: LocalDateTime, val prob: Int, val mm: Double, val snow: Boolean, val temp: Double)

    /** Ищем осадки в окне [сейчас; сейчас + lookAhead]. */
    fun findAlert(f: Forecast, s: WeatherSettings): Alert? {
        val now = LocalDateTime.now()
        val window = f.hours.filter {
            it.time.isAfter(now.minusMinutes(59)) && !it.time.isAfter(now.plusHours(s.lookAheadHours.toLong()))
        }
        val hit = window.filter { it.prob >= s.threshold && it.precip >= s.minMm }
        if (hit.isEmpty()) return null
        // продолжение осадков после окна — чтобы не дёргать повторно
        val after = f.hours.filter { it.time.isAfter(hit.last().time) }
            .takeWhile { it.prob >= s.threshold && it.precip >= s.minMm }
        val all = hit + after
        return Alert(
            start = hit.first().time,
            end = all.last().time.plusHours(1),
            prob = hit.maxOf { it.prob },
            mm = all.sumOf { it.precip },
            snow = hit.any { it.isSnow },
            temp = hit.first().temp,
        )
    }

    fun alertText(a: Alert, s: WeatherSettings, template: String): String {
        val now = LocalDateTime.now()
        val mins = java.time.Duration.between(now, a.start).toMinutes()
        val whenTxt = when {
            mins <= 15 -> "прямо сейчас"
            mins < 90 -> "в течение часа"
            else -> "к ${a.start.hour}:00"
        }
        return template
            .replace("{when}", whenTxt)
            .replace("{prob}", a.prob.toString())
            .replace("{mm}", mm(a.mm))
            .replace("{temp}", t(a.temp))
            .replace("{city}", s.city)
    }
}
