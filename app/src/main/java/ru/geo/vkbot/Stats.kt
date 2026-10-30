package ru.geo.vkbot

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Кэф-баланс и игровая статистика участников, отдельно по каждой беседе. */
object Stats {
    data class Player(
        val id: Long,
        var name: String,
        var kef: Double = 0.0,
        var wins: Int = 0,
        var losses: Int = 0,
        var shots: Int = 0,
        var deaths: Int = 0,
        var quiz: Int = 0,
        var bestFix: Int = 0,
    )

    private var file: File? = null
    private val data = mutableMapOf<Long, MutableMap<Long, Player>>()

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "stats.json")
        file = f
        if (!f.exists()) return
        runCatching {
            val root = JSONObject(f.readText())
            for (peerKey in root.keys()) {
                val peerObj = root.getJSONObject(peerKey)
                val map = mutableMapOf<Long, Player>()
                for (uidKey in peerObj.keys()) {
                    val o = peerObj.getJSONObject(uidKey)
                    val id = uidKey.toLong()
                    map[id] = Player(
                        id = id,
                        name = o.optString("name"),
                        kef = o.optDouble("kef", 0.0),
                        wins = o.optInt("wins"),
                        losses = o.optInt("losses"),
                        shots = o.optInt("shots"),
                        deaths = o.optInt("deaths"),
                        quiz = o.optInt("quiz"),
                        bestFix = o.optInt("bestFix"),
                    )
                }
                data[peerKey.toLong()] = map
            }
        }.onFailure { BotLog.add("⚠️ Статистика не прочиталась: ${it.message}") }
    }

    private fun save() {
        val f = file ?: return
        val root = JSONObject()
        for ((peer, map) in data) {
            val po = JSONObject()
            for ((id, p) in map) {
                po.put(
                    id.toString(),
                    JSONObject().put("name", p.name).put("kef", p.kef).put("wins", p.wins)
                        .put("losses", p.losses).put("shots", p.shots).put("deaths", p.deaths)
                        .put("quiz", p.quiz).put("bestFix", p.bestFix),
                )
            }
            root.put(peer.toString(), po)
        }
        runCatching { f.writeText(root.toString()) }
    }

    @Synchronized
    fun update(peer: Long, id: Long, name: String, block: (Player) -> Unit): Player {
        val map = data.getOrPut(peer) { mutableMapOf() }
        val p = map.getOrPut(id) { Player(id, name) }
        if (name.isNotBlank()) p.name = name
        block(p)
        p.kef = Math.round(p.kef * 100) / 100.0
        save()
        return p.copy()
    }

    @Synchronized
    fun get(peer: Long, id: Long): Player? = data[peer]?.get(id)?.copy()

    @Synchronized
    fun all(peer: Long): List<Player> = data[peer]?.values?.map { it.copy() } ?: emptyList()

    @Synchronized
    fun resetPeer(peer: Long) {
        data.remove(peer)
        save()
    }

    @Synchronized
    fun resetAll() {
        data.clear()
        save()
    }

    fun fmtKef(v: Double): String {
        val s = String.format(Locale("ru"), "%.2f", kotlin.math.abs(v)).trimEnd('0').trimEnd(',')
        return (if (v < 0) "-" else "+") + s
    }
}
