@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package ru.geo.vkbot

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConfigStore.init(this)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = AppColors) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    App()
                }
            }
        }
    }
}

private val AppColors = darkColorScheme(
    primary = Color(0xFF9CCC65),
    onPrimary = Color(0xFF102000),
    secondary = Color(0xFFFFD54F),
    background = Color(0xFF101410),
    surface = Color(0xFF161B16),
    surfaceVariant = Color(0xFF232A23),
    error = Color(0xFFFF6E6E),
)

private val tabs = listOf("Главная", "Правила", "Расписание", "Погода", "Настройки", "Лог")

@Composable
private fun App() {
    var tab by remember { mutableIntStateOf(0) }
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding(),
    ) {
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            tabs.forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
            }
        }
        Box(Modifier.fillMaxSize()) {
            when (tab) {
                0 -> HomeScreen()
                1 -> RulesScreen()
                2 -> ScheduleScreen()
                3 -> WeatherScreen()
                4 -> SettingsScreen()
                5 -> LogScreen()
            }
        }
    }
}

// ======================= Общие элементы =======================

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Числовое поле: хранит текст локально, сохраняет только корректное число. */
@Composable
private fun IntField(label: String, value: Int, modifier: Modifier = Modifier, range: IntRange = 0..100000, onChange: (Int) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { s ->
            text = s.filter { it.isDigit() }.take(6)
            text.toIntOrNull()?.let { if (it in range) onChange(it) }
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun DoubleField(label: String, value: Double, modifier: Modifier = Modifier, onChange: (Double) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { s ->
            text = s.replace(',', '.').filter { it.isDigit() || it == '.' || it == '-' }.take(12)
            text.toDoubleOrNull()?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

/** Список строк: превью + кнопка «Изменить» с диалогом. */
@Composable
private fun ListField(
    title: String,
    items: List<String>,
    hint: String,
    blankLineSeparated: Boolean = false,
    onSave: (List<String>) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$title: ${items.size} шт.", Modifier.weight(1f))
            TextButton(onClick = { open = true }) { Text("Изменить") }
        }
        items.take(2).forEach {
            Text("• " + it.replace('\n', ' ').take(70), fontSize = 13.sp, color = Color.Gray)
        }
    }
    if (open) {
        ListEditDialog(title, items, hint, blankLineSeparated, onDismiss = { open = false }) {
            onSave(it)
            open = false
        }
    }
}

@Composable
private fun ListEditDialog(
    title: String,
    items: List<String>,
    hint: String,
    blankLineSeparated: Boolean,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    val sep = if (blankLineSeparated) "\n\n" else "\n"
    var text by remember { mutableStateOf(items.joinToString(sep)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(hint, fontSize = 13.sp, color = Color.Gray)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp, max = 420.dp),
                )
            }
        },
        confirmButton = { Button(onClick = { onSave(splitList(text, blankLineSeparated)) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

private fun splitList(text: String, blankLineSeparated: Boolean): List<String> =
    if (blankLineSeparated) {
        text.replace("\r", "").split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotBlank() }
    } else {
        text.replace("\r", "").split('\n').map { it.trim() }.filter { it.isNotBlank() }
    }

private fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()

// ======================= Главная =======================

@SuppressLint("BatteryLife")
@Composable
private fun HomeScreen() {
    val ctx = LocalContext.current
    val cfg by ConfigStore.config.collectAsState()
    val status by BotLog.status.collectAsState()
    val sent by BotLog.sent.collectAsState()
    val received by BotLog.received.collectAsState()
    val scope = rememberCoroutineScope()
    var testText by remember { mutableStateOf("Проверка связи. {member}, {kef}. Учтём.") }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    var batteryOk by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(ctx.packageName)) }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Section("Бот") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (cfg.enabled) "ВКЛЮЧЁН" else "ВЫКЛЮЧЕН", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(status, fontSize = 13.sp, color = Color.Gray)
                }
                Switch(
                    checked = cfg.enabled,
                    onCheckedChange = { on ->
                        if (on && (cfg.token.isBlank() || cfg.groupId == 0L)) {
                            toast(ctx, "Сначала впиши токен и ID группы во вкладке «Настройки»")
                            return@Switch
                        }
                        ConfigStore.update { it.copy(enabled = on) }
                        if (on) BotService.start(ctx) else BotService.stop(ctx)
                    },
                )
            }
            Text("Получено сообщений: $received · отправлено: $sent")
            Text(
                "Беседы: " + if (cfg.knownPeers.isEmpty()) "пока ни одной (напиши что-нибудь в беседе после добавления бота)"
                else cfg.knownPeers.joinToString { if (it > 2_000_000_000L) "№${it - 2_000_000_000L}" else "лс $it" },
                fontSize = 13.sp,
            )
        }

        if (!batteryOk) {
            Section("⚠️ Экономия батареи") {
                Text("Android может усыплять бота. Разреши работать без ограничений:")
                Button(onClick = {
                    runCatching {
                        ctx.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")),
                        )
                    }.onFailure {
                        ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }) { Text("Отключить оптимизацию") }
                TextButton(onClick = { batteryOk = pm.isIgnoringBatteryOptimizations(ctx.packageName) }) { Text("Я разрешил, проверить") }
            }
        }

        Section("Тестовое сообщение") {
            OutlinedTextField(value = testText, onValueChange = { testText = it }, modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                scope.launch {
                    val r = BotEngine(scope).sendTest(testText)
                    toast(ctx, r)
                }
            }) { Text("Отправить в беседу") }
        }

        Section("Подсказка") {
            Text(
                "Команды в беседе: /помощь, /погода, /завтра, /выходные, /анекдот, /брут, /бракодел, /кэф, /кто <текст>.\n" +
                    "Чтобы бот видел все сообщения, а не только обращения к нему, дай ему в беседе права администратора.",
                fontSize = 13.sp,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ======================= Правила =======================

@Composable
private fun RulesScreen() {
    val cfg by ConfigStore.config.collectAsState()
    var editing by remember { mutableStateOf<Rule?>(null) }
    var isNew by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Реакции на сообщения", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Button(onClick = { editing = Rule(name = "Новое правило"); isNew = true }) { Text("+ Добавить") }
            }
            Text(
                "Проверяются сверху вниз, срабатывает первое подходящее. Подстановки: {name} {member} {member2} {kef} {pluskef} {minuskef} {weather}",
                Modifier.padding(horizontal = 12.dp),
                fontSize = 12.sp,
                color = Color.Gray,
            )
        }
        items(cfg.rules, key = { it.id }) { r ->
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 5.dp)
                    .clickable { editing = r; isNew = false },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name, fontWeight = FontWeight.Bold)
                        Text(
                            r.type.title + " · шанс ${r.chance}% · ответов ${r.responses.size}",
                            fontSize = 12.sp,
                            color = Color.Gray,
                        )
                        if (r.triggers.isNotEmpty()) {
                            Text(r.triggers.joinToString(", ").take(80), fontSize = 12.sp)
                        }
                    }
                    Switch(checked = r.enabled, onCheckedChange = { on ->
                        ConfigStore.update { c -> c.copy(rules = c.rules.map { if (it.id == r.id) it.copy(enabled = on) else it }) }
                    })
                }
            }
        }
        item { Spacer(Modifier.height(40.dp)) }
    }

    editing?.let { r ->
        RuleDialog(
            rule = r,
            isNew = isNew,
            onDismiss = { editing = null },
            onSave = { nr ->
                ConfigStore.update { c ->
                    c.copy(rules = if (isNew) c.rules + nr else c.rules.map { if (it.id == nr.id) nr else it })
                }
                editing = null
            },
            onDelete = {
                ConfigStore.update { c -> c.copy(rules = c.rules.filter { it.id != r.id }) }
                editing = null
            },
            onMove = { dir ->
                ConfigStore.update { c ->
                    val l = c.rules.toMutableList()
                    val i = l.indexOfFirst { it.id == r.id }
                    val j = i + dir
                    if (i >= 0 && j in l.indices) {
                        val t = l[i]; l[i] = l[j]; l[j] = t
                    }
                    c.copy(rules = l)
                }
            },
        )
    }
}

@Composable
private fun RuleDialog(
    rule: Rule,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (Rule) -> Unit,
    onDelete: () -> Unit,
    onMove: (Int) -> Unit,
) {
    var name by remember { mutableStateOf(rule.name) }
    var type by remember { mutableStateOf(rule.type) }
    var triggers by remember { mutableStateOf(rule.triggers.joinToString("\n")) }
    var responses by remember { mutableStateOf(rule.responses.joinToString("\n")) }
    var chance by remember { mutableIntStateOf(rule.chance) }
    var cooldown by remember { mutableIntStateOf(rule.cooldownSec) }
    var reply by remember { mutableStateOf(rule.reply) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Новое правило" else "Правило") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RuleType.entries.forEach { t ->
                        FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.title, fontSize = 12.sp) })
                    }
                }
                if (type != RuleType.RANDOM) {
                    OutlinedTextField(
                        triggers, { triggers = it },
                        label = { Text(if (type == RuleType.COMMAND) "Команды (без /), по одной в строке" else "Слова-триггеры, по одному в строке") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 90.dp),
                    )
                }
                OutlinedTextField(
                    responses, { responses = it },
                    label = { Text("Ответы, по одному в строке (\\n — перенос)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 360.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IntField("Шанс, %", chance, Modifier.weight(1f), 0..100) { chance = it }
                    IntField("Пауза, сек", cooldown, Modifier.weight(1f)) { cooldown = it }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { reply = !reply }) {
                    Checkbox(checked = reply, onCheckedChange = { reply = it })
                    Text("Отвечать с цитатой")
                }
                if (!isNew) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onMove(-1) }) { Text("↑ Выше") }
                        OutlinedButton(onClick = { onMove(1) }) { Text("↓ Ниже") }
                        TextButton(onClick = onDelete) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    rule.copy(
                        name = name.ifBlank { "Без названия" },
                        type = type,
                        triggers = splitList(triggers, false),
                        responses = splitList(responses, false),
                        chance = chance,
                        cooldownSec = cooldown,
                        reply = reply,
                    ),
                )
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

// ======================= Расписание и анекдоты =======================

private val dayNames = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

@Composable
private fun ScheduleScreen() {
    val cfg by ConfigStore.config.collectAsState()
    var editing by remember { mutableStateOf<Schedule?>(null) }
    var isNew by remember { mutableStateOf(false) }
    val j = cfg.jokes

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Section("😂 Анекдоты") {
                SwitchRow("Кидать анекдоты по расписанию", j.enabled) { on -> ConfigStore.update { it.copy(jokes = it.jokes.copy(enabled = on)) } }
                SwitchRow("18+ (с сайта)", j.adult) { on -> ConfigStore.update { it.copy(jokes = it.jokes.copy(adult = on)) } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IntField("Раз в N часов", j.intervalHours, Modifier.weight(1f), 1..24) { v -> ConfigStore.update { it.copy(jokes = it.jokes.copy(intervalHours = v)) } }
                    IntField("С (час)", j.fromHour, Modifier.weight(1f), 0..23) { v -> ConfigStore.update { it.copy(jokes = it.jokes.copy(fromHour = v)) } }
                    IntField("До (час)", j.toHour, Modifier.weight(1f), 0..24) { v -> ConfigStore.update { it.copy(jokes = it.jokes.copy(toHour = v)) } }
                }
                ListField(
                    "Свои анекдоты", j.ownJokes,
                    "Анекдоты разделяй ПУСТОЙ строкой. Они идут вперемешку с сайтом и выручают, если сайт лежит.",
                    blankLineSeparated = true,
                ) { l -> ConfigStore.update { it.copy(jokes = it.jokes.copy(ownJokes = l)) } }
            }
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Сообщения по расписанию", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Button(onClick = { editing = Schedule(name = "Новое"); isNew = true }) { Text("+ Добавить") }
            }
        }
        items(cfg.schedules, key = { it.id }) { s ->
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 5.dp)
                    .clickable { editing = s; isNew = false },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        String.format("%02d:%02d", s.hour, s.minute),
                        fontSize = 22.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(80.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(s.name, fontWeight = FontWeight.Bold)
                        Text(
                            s.kind.title + " · " + s.days.sorted().joinToString(" ") { dayNames[(it - 1).coerceIn(0, 6)] },
                            fontSize = 12.sp,
                            color = Color.Gray,
                        )
                    }
                    Switch(checked = s.enabled, onCheckedChange = { on ->
                        ConfigStore.update { c -> c.copy(schedules = c.schedules.map { if (it.id == s.id) it.copy(enabled = on) else it }) }
                    })
                }
            }
        }
        item { Spacer(Modifier.height(40.dp)) }
    }

    editing?.let { s ->
        ScheduleDialog(
            s, isNew,
            onDismiss = { editing = null },
            onSave = { ns ->
                ConfigStore.update { c ->
                    c.copy(schedules = if (isNew) c.schedules + ns else c.schedules.map { if (it.id == ns.id) ns else it })
                }
                editing = null
            },
            onDelete = {
                ConfigStore.update { c -> c.copy(schedules = c.schedules.filter { it.id != s.id }) }
                editing = null
            },
        )
    }
}

@Composable
private fun ScheduleDialog(
    s: Schedule,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (Schedule) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember { mutableStateOf(s.name) }
    var kind by remember { mutableStateOf(s.kind) }
    var hour by remember { mutableIntStateOf(s.hour) }
    var minute by remember { mutableIntStateOf(s.minute) }
    var days by remember { mutableStateOf(s.days) }
    var responses by remember { mutableStateOf(s.responses.joinToString("\n")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Новое сообщение" else "Расписание") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScheduleKind.entries.forEach { k ->
                        FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.title, fontSize = 12.sp) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IntField("Час", hour, Modifier.weight(1f), 0..23) { hour = it }
                    IntField("Минута", minute, Modifier.weight(1f), 0..59) { minute = it }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    dayNames.forEachIndexed { i, d ->
                        val n = i + 1
                        FilterChip(
                            selected = n in days,
                            onClick = { days = if (n in days) days - n else days + n },
                            label = { Text(d) },
                        )
                    }
                }
                if (kind != ScheduleKind.JOKE) {
                    OutlinedTextField(
                        responses, { responses = it },
                        label = {
                            Text(if (kind == ScheduleKind.WEATHER) "Заголовки перед сводкой (по одному в строке)" else "Фразы, по одной в строке")
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp, max = 360.dp),
                    )
                }
                if (!isNew) TextButton(onClick = onDelete) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    s.copy(
                        name = name.ifBlank { "Без названия" }, kind = kind, hour = hour, minute = minute,
                        days = days, responses = splitList(responses, false),
                    ),
                )
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

// ======================= Погода =======================

@Composable
private fun WeatherScreen() {
    val cfg by ConfigStore.config.collectAsState()
    val w = cfg.weather
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    fun upd(f: (WeatherSettings) -> WeatherSettings) = ConfigStore.update { it.copy(weather = f(it.weather)) }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Section("🌦 Погода") {
            SwitchRow("Погода включена (/погода, сводки)", w.enabled) { on -> upd { it.copy(enabled = on) } }
            var city by remember { mutableStateOf(w.city) }
            OutlinedTextField(city, { city = it; upd { s -> s.copy(city = it) } }, label = { Text("Город (для текста)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DoubleField("Широта", w.lat, Modifier.weight(1f)) { v -> upd { it.copy(lat = v) } }
                DoubleField("Долгота", w.lon, Modifier.weight(1f)) { v -> upd { it.copy(lon = v) } }
            }
            Text("Источник: Open-Meteo, бесплатно и без ключа.", fontSize = 12.sp, color = Color.Gray)
        }
        Section("☔ Предупреждение об осадках") {
            SwitchRow("Писать в беседу перед дождём/снегом", w.alertEnabled) { on -> upd { it.copy(alertEnabled = on) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IntField("Порог, %", w.threshold, Modifier.weight(1f), 1..100) { v -> upd { it.copy(threshold = v) } }
                DoubleField("Мин. мм/час", w.minMm, Modifier.weight(1f)) { v -> upd { it.copy(minMm = v) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IntField("Смотреть вперёд, ч", w.lookAheadHours, Modifier.weight(1f), 1..6) { v -> upd { it.copy(lookAheadHours = v) } }
                IntField("Проверять раз в, мин", w.checkMinutes, Modifier.weight(1f), 10..240) { v -> upd { it.copy(checkMinutes = v) } }
            }
            ListField("Фразы про дождь", w.rainTemplates, "Подстановки: {when} {prob} {mm} {temp} {city} {member} {kef}") { l -> upd { it.copy(rainTemplates = l) } }
            ListField("Фразы про снег", w.snowTemplates, "Подстановки: {when} {prob} {mm} {temp} {city} {member} {kef}") { l -> upd { it.copy(snowTemplates = l) } }
        }
        Section("Проверка") {
            Button(enabled = !loading, onClick = {
                loading = true
                scope.launch {
                    preview = BotEngine(scope).weatherPreview()
                    loading = false
                }
            }) { Text(if (loading) "Загружаю…" else "Показать прогноз сейчас") }
            if (preview.isNotBlank()) Text(preview, fontSize = 14.sp)
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ======================= Настройки =======================

@Composable
private fun SettingsScreen() {
    val ctx = LocalContext.current
    val cfg by ConfigStore.config.collectAsState()
    var withToken by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(ConfigStore.exportJson(withToken).toByteArray()) }
            toast(ctx, "Сохранено")
        }.onFailure { toast(ctx, "Ошибка: ${it.message}") }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            ConfigStore.importJson(text)
            toast(ctx, "Загружено")
        }.onFailure { toast(ctx, "Не получилось: ${it.message}") }
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Section("🔑 Подключение к ВК") {
            var token by remember { mutableStateOf(cfg.token) }
            var show by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = token,
                onValueChange = { token = it.trim(); ConfigStore.update { c -> c.copy(token = token) } },
                label = { Text("Ключ доступа сообщества") },
                singleLine = true,
                visualTransformation = if (show) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { show = !show }) { Text(if (show) "Скрыть" else "Показать") }
            var gid by remember { mutableStateOf(if (cfg.groupId == 0L) "" else cfg.groupId.toString()) }
            OutlinedTextField(
                value = gid,
                onValueChange = { s ->
                    gid = s.filter { it.isDigit() }
                    ConfigStore.update { c -> c.copy(groupId = gid.toLongOrNull() ?: 0L) }
                },
                label = { Text("ID группы (только цифры)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Text("После изменения токена выключи и включи бота.", fontSize = 12.sp, color = Color.Gray)
        }

        Section("💬 Куда писать по расписанию") {
            var peers by remember { mutableStateOf(cfg.targetPeers.joinToString(", ")) }
            OutlinedTextField(
                value = peers,
                onValueChange = { s ->
                    peers = s
                    val l = s.split(',', ' ', ';').mapNotNull { it.trim().toLongOrNull() }
                        .map { if (it < 2_000_000_000L) it + 2_000_000_000L else it }
                    ConfigStore.update { c -> c.copy(targetPeers = l) }
                },
                label = { Text("Номера бесед через запятую (пусто = во все)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Известные беседы: " + cfg.knownPeers.filter { it > 2_000_000_000L }
                    .joinToString { (it - 2_000_000_000L).toString() }.ifBlank { "нет" },
                fontSize = 12.sp,
            )
            if (cfg.knownPeers.isNotEmpty()) {
                TextButton(onClick = { ConfigStore.update { it.copy(knownPeers = emptyList()) } }) { Text("Забыть известные беседы") }
            }
        }

        Section("🤫 Поведение") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IntField("Тишина с (час)", cfg.quietFrom, Modifier.weight(1f), 0..23) { v -> ConfigStore.update { it.copy(quietFrom = v) } }
                IntField("до (час)", cfg.quietTo, Modifier.weight(1f), 0..23) { v -> ConfigStore.update { it.copy(quietTo = v) } }
            }
            Text("В тихие часы бот не реагирует на слова и не шлёт анекдоты/алерты. Команды и расписание работают.", fontSize = 12.sp, color = Color.Gray)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IntField("Задержка от, сек", cfg.delayMinSec, Modifier.weight(1f), 0..60) { v -> ConfigStore.update { it.copy(delayMinSec = v) } }
                IntField("до, сек", cfg.delayMaxSec, Modifier.weight(1f), 0..120) { v -> ConfigStore.update { it.copy(delayMaxSec = v) } }
            }
        }

        Section("👷 Участники и приветствие") {
            ListField("Участники ({member})", cfg.members, "По одному в строке. Если список пуст — бот возьмёт участников беседы из ВК.") { l ->
                ConfigStore.update { it.copy(members = l) }
            }
            ListField("Приветствие при добавлении в беседу", cfg.greeting, "Вариантов может быть несколько, по одному в строке.") { l ->
                ConfigStore.update { it.copy(greeting = l) }
            }
        }

        Section("💾 Импорт / экспорт") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { withToken = !withToken }) {
                Checkbox(checked = withToken, onCheckedChange = { withToken = it })
                Text("Включить токен в файл (не делись таким файлом!)")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { exportLauncher.launch("vk-geo-bot.json") }) { Text("Экспорт") }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("Импорт") }
            }
            HorizontalDivider()
            TextButton(onClick = { confirmReset = true }) {
                Text("Вернуть стандартные фразы и правила", color = MaterialTheme.colorScheme.error)
            }
        }
        Text(
            "Версия ${runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrDefault("?")}",
            Modifier.padding(16.dp),
            fontSize = 12.sp,
            color = Color.Gray,
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Сбросить?") },
            text = { Text("Правила, расписание, участники и фразы вернутся к стандартным. Токен и беседы останутся.") },
            confirmButton = { Button(onClick = { ConfigStore.resetContent(); confirmReset = false }) { Text("Сбросить") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Отмена") } },
        )
    }
}

// ======================= Лог =======================

@Composable
private fun LogScreen() {
    val lines by BotLog.lines.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Последние события", Modifier.weight(1f), fontWeight = FontWeight.Bold)
            TextButton(onClick = { BotLog.clear() }) { Text("Очистить") }
        }
        if (lines.isEmpty()) {
            Text("Пока пусто. Включи бота на главной.", Modifier.padding(12.dp), color = Color.Gray)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(lines) { l ->
                Text(
                    l,
                    Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
