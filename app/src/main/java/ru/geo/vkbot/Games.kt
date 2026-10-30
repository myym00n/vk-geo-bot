package ru.geo.vkbot

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** Кто написал и на кого указал (упоминанием или ответом на сообщение). */
data class GameCtx(
    val peer: Long,
    val fromId: Long,
    val fromName: String,
    val targetId: Long?,
    val targetName: String?,
    val args: String,
)

/** Игры: дуэли, рулетка, викторина, фикс, монетка, кубик, шар, пара, гороскоп, баланс, топ. */
class Games(
    private val scope: CoroutineScope,
    private val send: suspend (peer: Long, text: String) -> Unit,
    private val members: suspend (peer: Long) -> List<String>,
) {
    private fun m(id: Long, name: String) = "[id$id|$name]"

    // ---------------- Дуэль ----------------

    private data class Duel(
        val challengerId: Long,
        val challengerName: String,
        val targetId: Long?,
        val targetName: String?,
        val stake: Double,
        val created: Long = System.currentTimeMillis(),
    )

    private val duels = ConcurrentHashMap<Long, Duel>()

    private val duelWeapons = listOf(
        "рейками", "вешками", "штативами", "отражателями", "рулетками на 50 метров",
        "колышками", "топорами для колышков", "пачками старых полевых журналов",
        "кружками с недопитым кофе", "кирпичами от аккумуляторов",
    )

    private val duelHits = listOf(
        "%s промахнулся — ровно на величину своей невязки",
        "%s прицелился через окуляр, но забыл снять крышку",
        "%s уронил оружие себе на ногу. Бракодел",
        "%s попал, но в заказчика. Тот даже не заметил",
        "%s поймал фикс и пошёл в атаку",
        "%s спрятался за тахеометром за 800 тысяч",
        "%s крикнул «у меня ГНСС!» и это никого не впечатлило",
        "%s отвлёкся на звонок «где съёмка?»",
        "%s размахнулся, но рулетка смоталась обратно",
        "%s сделал ход конём — то есть замкнутым ходом",
    )

    private val duelFinish = listOf(
        "%s повержен и отправлен снимать колодцы под дождём",
        "%s лежит. Пикет поставлен прямо на нём",
        "%s отдал кэф и уполз в офис",
        "%s сдался и обещал больше не бракоделить (врёт)",
        "%s проиграл и теперь неделю держит рейку",
        "%s улетел за пределы съёмочного обоснования",
    )

    private suspend fun duel(c: GameCtx) {
        val existing = duels[c.peer]
        if (existing != null && System.currentTimeMillis() - existing.created < 120_000) {
            send(c.peer, "⚔️ Тут уже идёт вызов от ${existing.challengerName}. Подожди или прими его: /принять")
            return
        }
        if (c.targetId == c.fromId) {
            send(c.peer, "Дуэль с самим собой — это называется «работа в офисе». /дуэль @кто-то")
            return
        }
        val stake = c.args.replace(',', '.').split(' ').firstNotNullOfOrNull { it.toDoubleOrNull() }
            ?.coerceIn(0.05, 1.0) ?: 0.2
        val d = Duel(c.fromId, c.fromName, c.targetId, c.targetName, stake)
        duels[c.peer] = d
        val text = if (c.targetId != null) {
            "⚔️ ${m(c.fromId, c.fromName)} вызывает ${m(c.targetId, c.targetName ?: "соперника")} на дуэль!\n" +
                "Ставка: ${Stats.fmtKef(stake).trimStart('+')} кэфа.\n" +
                "${c.targetName}, пиши /принять или /отказ. На раздумья 2 минуты."
        } else {
            "⚔️ ${m(c.fromId, c.fromName)} ищет, кому набить морду рейкой!\n" +
                "Ставка: ${Stats.fmtKef(stake).trimStart('+')} кэфа. Кто не ссыкло — /принять (2 минуты)"
        }
        send(c.peer, text)
        scope.launch {
            delay(120_000)
            if (duels[c.peer] === d) {
                duels.remove(c.peer)
                val coward = d.targetName ?: "никто"
                send(c.peer, "⌛ Дуэль протухла. $coward зассал. Учтём.")
                if (d.targetId != null) Stats.update(c.peer, d.targetId, d.targetName ?: "") { it.kef -= 0.05 }
            }
        }
    }

    private suspend fun accept(c: GameCtx) {
        val d = duels[c.peer]
        if (d == null) {
            send(c.peer, "Принимать нечего. Вызови кого-нибудь: /дуэль @имя")
            return
        }
        if (d.challengerId == c.fromId) {
            send(c.peer, "Сам вызвал — сам и жди, нетерпеливый ты наш.")
            return
        }
        if (d.targetId != null && d.targetId != c.fromId) {
            send(c.peer, "${c.fromName}, вызов не тебе. Не лезь, а то тоже получишь.")
            return
        }
        duels.remove(c.peer)
        val aId = d.challengerId
        val aName = d.challengerName
        val bId = c.fromId
        val bName = c.fromName
        val weapon = duelWeapons.random()
        val sb = StringBuilder("⚔️ ДУЭЛЬ: $aName vs $bName\nОружие: $weapon\n\n")
        val names = listOf(aName, bName)
        duelHits.shuffled().take(3).forEachIndexed { i, h ->
            sb.append("${i + 1}. ").append(h.format(names[i % 2])).append("\n")
        }
        val aWins = Random.nextBoolean()
        val wId = if (aWins) aId else bId
        val wName = if (aWins) aName else bName
        val lId = if (aWins) bId else aId
        val lName = if (aWins) bName else aName
        sb.append("\n💀 ").append(duelFinish.random().format(lName)).append("\n")
        sb.append("🏆 Победил ${m(wId, wName)}! ${Stats.fmtKef(d.stake)} кэф победителю, ${Stats.fmtKef(-d.stake)} проигравшему.")
        Stats.update(c.peer, wId, wName) { it.kef += d.stake; it.wins++ }
        Stats.update(c.peer, lId, lName) { it.kef -= d.stake; it.losses++ }
        send(c.peer, sb.toString())
    }

    private suspend fun decline(c: GameCtx) {
        val d = duels[c.peer] ?: run {
            send(c.peer, "Отказываться не от чего. Но рвение учтём.")
            return
        }
        if (d.challengerId == c.fromId) {
            duels.remove(c.peer)
            send(c.peer, "${c.fromName} передумал драться. Слабак. -0,05 кэф.")
            Stats.update(c.peer, c.fromId, c.fromName) { it.kef -= 0.05 }
            return
        }
        if (d.targetId == c.fromId) {
            duels.remove(c.peer)
            send(c.peer, "🐔 ${c.fromName} отказался от дуэли. Позор. -0,1 кэф за трусость.")
            Stats.update(c.peer, c.fromId, c.fromName) { it.kef -= 0.1 }
            return
        }
        send(c.peer, "Тебя никто и не звал, ${c.fromName}.")
    }

    // ---------------- Рулетка ----------------

    private suspend fun roulette(c: GameCtx) {
        val bang = Random.nextInt(6) == 0
        val p = Stats.update(c.peer, c.fromId, c.fromName) {
            it.shots++
            if (bang) {
                it.deaths++
                it.kef -= 0.3
            } else {
                it.kef += 0.05
            }
        }
        val text = if (bang) {
            listOf(
                "💥 БАХ! ${c.fromName} словил пулю. Тахеометр в шоке. -0,3 кэф. Всего смертей: ${p.deaths}",
                "💥 БАХ! ${c.fromName} отлетел к реперу. Высота тела теперь известна. -0,3 кэф",
                "💥 БАХ! Начальник услышал выстрел и снял с ${c.fromName} 0,3 кэфа. За шум.",
            ).random()
        } else {
            listOf(
                "🔫 Щёлк… ${c.fromName} выжил. +0,05 кэф за смелость. Баланс: ${Stats.fmtKef(p.kef)}",
                "🔫 Щёлк. Пусто. ${c.fromName} живёт ещё один рабочий день. +0,05 кэф",
                "🔫 Осечка. Как у ${c.fromName} с полилиниями. +0,05 кэф",
            ).random()
        }
        send(c.peer, text)
    }

    // ---------------- Викторина ----------------

    private data class Question(val q: String, val answers: List<String>, val shown: String)

    private val questions = listOf(
        Question("Сколько градусов в полном круге?", listOf("360"), "360"),
        Question("Сколько гон (градов) в полном круге?", listOf("400"), "400"),
        Question("Сколько угловых минут в одном градусе?", listOf("60"), "60"),
        Question("Каким прибором измеряют превышения при геометрическом нивелировании?", listOf("нивелир"), "нивелир"),
        Question("Прибор, который меряет и углы, и расстояния?", listOf("тахеометр"), "тахеометр"),
        Question("Как называется закреплённая на местности точка с известной высотой?", listOf("репер"), "репер"),
        Question("Как называется разность высот двух точек?", listOf("превышение"), "превышение"),
        Question("Линия на плане, соединяющая точки с одинаковой высотой?", listOf("горизонталь", "изогипса"), "горизонталь"),
        Question("Расхождение между измеренной и теоретической суммой — это …?", listOf("невязка"), "невязка"),
        Question("Масштаб 1:500. Сколько метров на местности в 1 см плана?", listOf("5"), "5 м"),
        Question("Масштаб 1:2000. Сколько метров в 1 см плана?", listOf("20"), "20 м"),
        Question("Масштаб 1:1000. Сколько метров в 1 см плана?", listOf("10"), "10 м"),
        Question("Сколько метров в морской миле?", listOf("1852"), "1852"),
        Question("Как называется ход, который начинается и заканчивается на одной точке?", listOf("замкнутый"), "замкнутый"),
        Question("Как называется точка съёмки, на которую ставят веху с отражателем?", listOf("пикет"), "пикет"),
        Question("Какая система высот принята в России? (одно слово)", listOf("балтийская"), "Балтийская"),
        Question("Максимальное значение румба, в градусах?", listOf("90"), "90°"),
        Question("От какого направления отсчитывают дирекционный угол? (сторона света)", listOf("север"), "север (ось X)"),
        Question("Как называют решение ГНСС, когда неоднозначность разрешена? (одно слово, как в поле говорят)", listOf("фикс", "fix", "fixed"), "фикс"),
        Question("Сколько сантиметров в одном дюйме?", listOf("2,54", "2.54", "254"), "2,54"),
        Question("Как называется угол между направлением на север и направлением на точку, отсчитываемый по часовой стрелке от истинного меридиана?", listOf("азимут"), "азимут"),
        Question("Как называется отвесная линия, проходящая через точку? (одним словом, тоже прибор строителей)", listOf("отвес"), "отвес"),
        Question("Деревянная или алюминиевая палка с делениями для нивелирования?", listOf("рейка"), "рейка"),
        Question("Сколько секунд в одной угловой минуте?", listOf("60"), "60"),
        Question("Чертёж местности в крупном масштабе без учёта кривизны Земли — это …?", listOf("план", "топоплан"), "план"),
    )

    private data class Quiz(val question: Question, val started: Long = System.currentTimeMillis())

    private val quizzes = ConcurrentHashMap<Long, Quiz>()

    private suspend fun quiz(c: GameCtx) {
        if (quizzes.containsKey(c.peer)) {
            send(c.peer, "Вопрос уже висит, отвечайте давайте: ${quizzes[c.peer]?.question?.q}")
            return
        }
        val q = questions.random()
        val quiz = Quiz(q)
        quizzes[c.peer] = quiz
        send(c.peer, "🧠 ВИКТОРИНА (60 сек, +0,1 кэф первому)\n${q.q}")
        scope.launch {
            delay(60_000)
            if (quizzes[c.peer] === quiz) {
                quizzes.remove(c.peer)
                send(c.peer, "⌛ Время вышло, бракоделы. Правильный ответ: ${q.shown}. Всем -0 кэф, потому что и так в минусе.")
            }
        }
    }

    /** Проверка ответа на викторину. true — сообщение было правильным ответом. */
    suspend fun checkAnswer(peer: Long, fromId: Long, fromName: String, text: String): Boolean {
        val quiz = quizzes[peer] ?: return false
        val clean = " " + text.norm().replace(Regex("[^\\p{L}\\p{N},.]+"), " ").trim() + " "
        val ok = quiz.question.answers.any { a -> clean.contains(" ${a.norm()} ") }
        if (!ok) return false
        if (quizzes.remove(peer) == null) return false
        val p = Stats.update(peer, fromId, fromName) { it.kef += 0.1; it.quiz++ }
        send(peer, "✅ ${m(fromId, fromName)} прав: ${quiz.question.shown}! +0,1 кэф. Правильных ответов: ${p.quiz}")
        return true
    }

    // ---------------- Фикс ----------------

    private val fixRecords = ConcurrentHashMap<Long, Pair<LocalDate, Pair<String, Int>>>()

    private suspend fun fix(c: GameCtx) {
        val sec = when (Random.nextInt(10)) {
            0 -> Random.nextInt(1, 6)
            in 1..6 -> Random.nextInt(6, 120)
            else -> Random.nextInt(120, 1200)
        }
        val today = LocalDate.now()
        val rec = fixRecords[c.peer]?.takeIf { it.first == today }?.second
        val isRecord = rec == null || sec < rec.second
        if (isRecord) fixRecords[c.peer] = today to (c.fromName to sec)
        Stats.update(c.peer, c.fromId, c.fromName) {
            if (it.bestFix == 0 || sec < it.bestFix) {
                it.bestFix = sec
            }
            if (sec <= 5) {
                it.kef += 0.1
            }
            if (sec >= 600) {
                it.kef -= 0.05
            }
        }
        val t = if (sec >= 60) "${sec / 60} мин ${sec % 60} сек" else "$sec сек"
        val comment = when {
            sec <= 5 -> "Это вообще законно? +0,1 кэф"
            sec < 60 -> "Нормально, жить можно."
            sec < 300 -> "Под деревом стоишь, что ли?"
            sec < 600 -> "Пока ловил — заказчик успел передумать."
            else -> "Это не фикс, это пенсия. -0,05 кэф"
        }
        val recTxt = if (isRecord) "\n🏅 Рекорд дня!" else "\nРекорд дня: ${rec?.first} — ${rec?.second} сек"
        send(c.peer, "📡 ${c.fromName} ловит фикс… поймал за $t. $comment$recTxt")
    }

    // ---------------- Мелкие игры ----------------

    private val ballAnswers = listOf(
        "Однозначно да. Фикс получен.",
        "Нет. Даже не думай.",
        "В пределах допуска — да.",
        "Невязка слишком большая, переспроси.",
        "Спроси у начальника. Он скажет «нет».",
        "Да, но потом будешь переделывать.",
        "Звёзды говорят «да», тахеометр говорит «нет».",
        "100%. Как то, что заказчик не заплатит вовремя.",
        "Нет, ты бракодел.",
        "Скорее да, чем нет. Учтём.",
        "Сигнал потерян. Встань на открытое место и спроси снова.",
        "Даже ГНСС не знает.",
    )

    private val horoscopes = listOf(
        "сегодня фикс будет ловиться с первой попытки. Шучу. Бери пауэрбанк.",
        "звёзды советуют не брать ничего в работу. Особенно в рот.",
        "день удачный для съёмки колодцев. Для тебя — неудачный.",
        "сегодня тебе дадут кэф. Отрицательный.",
        "избегай заказчиков, луж и Брута.",
        "Марс в зоне МСК-52 — можно смело закрывать ходы с невязкой.",
        "кто-то в офисе считает тебя бракоделом. И он прав.",
        "сегодня твоя рейка будет стоять ровнее, чем обычно. Это намёк.",
        "переделывать придётся, но не сегодня. Сегодня — отрицать.",
        "удача на твоей стороне до 14:00. Потом домой.",
        "день хорош для того, чтобы свалить всё на прибор.",
    )

    private val pairs = listOf(
        "Пара дня: %s и %s — сегодня вместе держат одну рейку на двоих.",
        "Пара дня: %s и %s — замкнутый ход, без невязки, только любовь.",
        "Пара дня: %s и %s — один снимает, второй переделывает.",
        "Пара дня: %s и %s — едут на объект в одной машине и молчат три часа.",
        "Пара дня: %s и %s — делят один пауэрбанк и одну зарплату.",
    )

    // ---------------- Баланс и топ ----------------

    private suspend fun balance(c: GameCtx) {
        val id = c.targetId ?: c.fromId
        val name = c.targetName ?: c.fromName
        val p = Stats.get(c.peer, id)
        if (p == null) {
            send(c.peer, "💰 $name: кэф 0. Ни одного поступка. Подозрительно.")
            return
        }
        send(
            c.peer,
            "💰 ${p.name}: кэф ${Stats.fmtKef(p.kef)}\n" +
                "⚔️ Дуэли: ${p.wins} побед / ${p.losses} поражений\n" +
                "🔫 Рулетка: ${p.shots} выстрелов, ${p.deaths} смертей\n" +
                "🧠 Викторина: ${p.quiz} правильных\n" +
                "📡 Лучший фикс: ${if (p.bestFix > 0) "${p.bestFix} сек" else "не ловил"}",
        )
    }

    private suspend fun top(c: GameCtx) {
        val list = Stats.all(c.peer).sortedByDescending { it.kef }
        if (list.isEmpty()) {
            send(c.peer, "Топ пуст. Никто ничего не сделал. Как обычно.")
            return
        }
        val medals = listOf("🥇", "🥈", "🥉")
        val sb = StringBuilder("🏆 ТОП ПО КЭФУ:\n")
        list.take(15).forEachIndexed { i, p ->
            sb.append(medals.getOrElse(i) { "${i + 1}." }).append(" ${p.name}: ${Stats.fmtKef(p.kef)}\n")
        }
        val last = list.last()
        if (list.size > 2) sb.append("\n🗑 Главный бракодел беседы: ${last.name}")
        send(c.peer, sb.toString().trim())
    }

    val helpText = """
        🎮 Игры:
        /дуэль @кто [ставка] — вызвать на дуэль (без @ — любого желающего)
        /принять, /отказ — ответ на дуэль
        /рулетка — русская рулетка (1 из 6), выжил +0,05, умер -0,3
        /викторина — геодезический вопрос, первый правильный +0,1
        /фикс — кто быстрее поймает фикс
        /монетка, /кубик [N], /шар <вопрос>
        /пара — пара дня, /гороскоп — прогноз на день
        /баланс [@кто] — кэф и статистика, /топ — рейтинг по кэфу
    """.trimIndent()

    /** true — команда обработана играми. */
    suspend fun handle(cmd: String, c: GameCtx): Boolean {
        when (cmd) {
            "дуэль", "дуель", "duel" -> duel(c)
            "принять", "принимаю", "го" -> accept(c)
            "отказ", "отказаться" -> decline(c)
            "рулетка" -> roulette(c)
            "викторина", "вопрос" -> quiz(c)
            "фикс" -> fix(c)
            "монетка", "монета" -> send(c.peer, "🪙 ${c.fromName} подбрасывает… " + if (Random.nextBoolean()) "Орёл!" else "Решка!")
            "кубик", "куб" -> {
                val n = c.args.split(' ').firstNotNullOfOrNull { it.toIntOrNull() }?.coerceIn(2, 1000) ?: 6
                send(c.peer, "🎲 ${c.fromName} кидает кубик d$n: ${Random.nextInt(1, n + 1)}")
            }
            "шар", "8" -> {
                val q = c.args.ifBlank { "…" }
                send(c.peer, "🎱 «$q»\n${ballAnswers.random()}")
            }
            "пара" -> {
                val l = members(c.peer).shuffled()
                if (l.size < 2) send(c.peer, "Пару не из кого собрать.")
                else send(c.peer, "💞 " + pairs.random().format(l[0], l[1]))
            }
            "гороскоп", "предсказание" -> send(c.peer, "🔮 ${c.fromName}, ${horoscopes.random()}")
            "баланс", "мойкэф", "стата", "статистика" -> balance(c)
            "топ", "рейтинг" -> top(c)
            "игры" -> send(c.peer, helpText)
            else -> return false
        }
        return true
    }
}
