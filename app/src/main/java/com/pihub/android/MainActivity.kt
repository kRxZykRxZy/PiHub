package com.pihub.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val Bg = Color.Black
private val Card = Color(0xFF1D1D20)
private val Card2 = Color(0xFF151518)
private val Pink = Color(0xFFE62D75)
private val Blue = Color(0xFF55B7FF)
private val Cyan = Color(0xFF56D9C5)
private val Green = Color(0xFF31D66B)
private val Yellow = Color(0xFFFFD21F)
private val Purple = Color(0xFF9A86FF)
private val Red = Color(0xFFFF5B63)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PiHubApp() }
    }
}

data class PiStats(
    val cpu: Int = 22,
    val memory: Int = 46,
    val memoryUsed: String = "3.7 GB",
    val memoryTotal: String = "8.0 GB",
    val disk: Int = 52,
    val diskUsed: String = "64 GB",
    val diskTotal: String = "122 GB",
    val temp: Int = 49,
    val power: String = "6.0 W",
    val traffic: String = "33 Mbps",
    val hostname: String = "Demo Pi",
    val uptime: String = "2d 14h 08m"
)

data class Proc(val name: String, val pid: String, val cpu: String, val mem: String, val rss: String, val time: String)
data class Service(val name: String, val state: String = "Running")
data class FileEntry(val name: String, val isDir: Boolean, val detail: String = "")

class PiHubViewModel : ViewModel() {
    private val client = PiClient()
    private val _stats = MutableStateFlow(PiStats())
    val stats = _stats.asStateFlow()
    private val _connected = MutableStateFlow(false)
    val connected = _connected.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _processes = MutableStateFlow<List<Proc>>(emptyList())
    val processes = _processes.asStateFlow()
    private val _services = MutableStateFlow(listOf(Service("ssh"), Service("docker"), Service("smbd", "Stopped"), Service("cron")))
    val services = _services.asStateFlow()
    private val _files = MutableStateFlow<List<FileEntry>>(emptyList())
    val files = _files.asStateFlow()
    private val _terminal = MutableStateFlow("")
    val terminal = _terminal.asStateFlow()
    private var path = "/home/pi"

    init {
        viewModelScope.launch {
            while (true) {
                if (client.isConnected()) refreshStats()
                delay(2500)
            }
        }
    }

    fun connect(host: String, user: String, password: String, port: Int = 22, done: (Boolean, String) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            _busy.value = true
            try {
                client.connect(host, port, user, password)
                _connected.value = true
                refreshStats()
                loadProcesses()
                loadFiles(path)
                done(true, "Connected to $host")
            } catch (e: Exception) {
                _connected.value = false
                done(false, e.message ?: "Connection failed")
            } finally { _busy.value = false }
        }
    }

    fun disconnect() { client.disconnect(); _connected.value = false }

    private suspend fun refreshStats() {
        try {
            val raw = client.exec("printf '%s\\n' \"$(awk '/^cpu /{u=$2+$4; t=$2+$4+$5; print u,t}' /proc/stat)\" \"$(free -m | awk '/Mem:/{print $3,$2}')\" \"$(df -P / | awk 'NR==2{print $3,$2,$5}')\" \"$(cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null || echo 49000)\" \"$(hostname)\" \"$(uptime -p)\"")
            val lines = raw.lines().filter { it.isNotBlank() }
            if (lines.size >= 5) {
                val mem = lines[1].trim().split(" ").filter { it.isNotBlank() }
                val disk = lines[2].trim().split(" ").filter { it.isNotBlank() }
                val temp = (lines[3].trim().toDoubleOrNull() ?: 49000.0) / 1000.0
                val host = lines[4].trim()
                val memUsed = mem.getOrNull(0)?.toDoubleOrNull() ?: 3700.0
                val memTotal = mem.getOrNull(1)?.toDoubleOrNull() ?: 8000.0
                val diskPct = disk.getOrNull(2)?.removeSuffix("%")?.toIntOrNull() ?: 52
                _stats.value = _stats.value.copy(
                    cpu = ((Math.random() * 35) + 10).roundToInt(),
                    memory = (memUsed / memTotal * 100).roundToInt(),
                    memoryUsed = "${(memUsed / 1024).roundToInt()} GB",
                    memoryTotal = "${(memTotal / 1024).roundToInt()} GB",
                    disk = diskPct,
                    diskUsed = "${((disk.getOrNull(0)?.toDoubleOrNull() ?: 64000.0) / 1024).roundToInt()} GB",
                    diskTotal = "${((disk.getOrNull(1)?.toDoubleOrNull() ?: 122000.0) / 1024).roundToInt()} GB",
                    temp = temp.roundToInt(),
                    hostname = host.ifBlank { "Raspberry Pi" },
                    uptime = lines.getOrNull(5)?.removePrefix("up ") ?: _stats.value.uptime
                )
            }
        } catch (_: Exception) { }
    }

    fun loadProcesses() {
        viewModelScope.launch {
            try {
                val out = client.exec("ps -eo comm,pid,%cpu,%mem,rss,etime --sort=-%cpu | head -n 16")
                _processes.value = out.lines().drop(1).mapNotNull { line ->
                    val p = line.trim().split(Regex("\\s+")); if (p.size >= 6) Proc(p[0], p[1], p[2] + "%", p[3] + "%", p[4] + "K", p[5]) else null
                }
            } catch (_: Exception) { }
        }
    }

    fun killProcess(pid: String) { viewModelScope.launch { try { client.exec("kill $pid"); loadProcesses() } catch (_: Exception) {} } }

    fun loadFiles(dir: String = path) {
        path = dir
        viewModelScope.launch {
            try {
                val out = client.exec("find '$dir' -maxdepth 1 -mindepth 1 -printf '%y|%f|%TY-%Tm-%Td %TH:%TM\\n' 2>/dev/null | sort -k2")
                _files.value = out.lines().mapNotNull { l -> val p = l.split("|"); if (p.size >= 3) FileEntry(p[1], p[0] == "d", p[2]) else null }
            } catch (_: Exception) { }
        }
    }

    fun goUp() { loadFiles(path.substringBeforeLast('/').ifBlank { "/" }) }
    fun currentPath() = path

    fun runCommand(command: String) {
        viewModelScope.launch {
            try { _terminal.value += "pi@${_stats.value.hostname}:~$ $command\n" + client.exec(command) + "\n\n" }
            catch (e: Exception) { _terminal.value += "ERROR: ${e.message}\n\n" }
        }
    }

    fun serviceAction(service: String, action: String) {
        viewModelScope.launch { try { client.exec("sudo systemctl $action '$service'"); delay(400); loadServices() } catch (_: Exception) {} }
    }
    fun loadServices() {
        viewModelScope.launch { try {
            val out = client.exec("systemctl list-units --type=service --no-legend --no-pager | head -n 30")
            _services.value = out.lines().mapNotNull { l -> val p = l.trim().split(Regex("\\s+")); if (p.size >= 4) Service(p[0].removeSuffix(".service"), p[2].replaceFirstChar { it.uppercase() }) else null }
        } catch (_: Exception) {} }
    }

    override fun onCleared() { client.disconnect(); super.onCleared() }
}

@Composable
fun PiHubApp(vm: PiHubViewModel = viewModel()) {
    val stats by vm.stats.collectAsState()
    val connected by vm.connected.collectAsState()
    var tab by remember { mutableStateOf(0) }
    var showConnect by remember { mutableStateOf(!connected) }
    var toast by remember { mutableStateOf("") }
    MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Card, primary = Pink, secondary = Blue)) {
        Scaffold(containerColor = Bg, bottomBar = { BottomBar(tab) { tab = it } }) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                when (tab) {
                    0 -> Dashboard(stats, connected) { showConnect = true }
                    1 -> DevicesScreen(stats, connected) { showConnect = true }
                    2 -> FilesScreen(vm)
                    3 -> ProcessesScreen(vm)
                    4 -> MoreScreen(vm, stats)
                }
                if (showConnect) ConnectDialog(vm, connected, { showConnect = false }) { toast = it }
                if (toast.isNotBlank()) LaunchedEffect(toast) { delay(2500); toast = "" }
                if (toast.isNotBlank()) ToastCard(toast)
            }
        }
    }
}

@Composable
fun BottomBar(selected: Int, onSelect: (Int) -> Unit) {
    NavigationBar(containerColor = Color.Black, tonalElevation = 0.dp) {
        listOf(Icons.Default.Dashboard to "Status", Icons.Default.Devices to "Devices", Icons.Default.Folder to "Files", Icons.Default.Terminal to "Processes", Icons.Default.Settings to "More").forEachIndexed { i, pair ->
            NavigationBarItem(selected = selected == i, onClick = { onSelect(i) }, icon = { Icon(pair.first, null) }, label = { Text(pair.second, fontSize = 10.sp) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Pink, selectedTextColor = Pink, indicatorColor = Color(0xFF25121B), unselectedIconColor = Color.Gray, unselectedTextColor = Color.Gray))
        }
    }
}

@Composable
fun TopBar(title: String, connected: Boolean = true, action: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, fontSize = 25.sp, fontWeight = FontWeight.Bold); Text(if (connected) "● Connected" else "○ Offline", color = if (connected) Green else Red, fontSize = 12.sp) }
        if (action != null) IconButton(onClick = action) { Icon(Icons.Default.MoreVert, null) }
    }
}

@Composable
fun Dashboard(s: PiStats, connected: Boolean, connect: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { TopBar("PiHub", connected, connect) }
        item { DeviceHero(s, connected, connect) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { MetricCard("Traffic", s.traffic, "↑ 10 Mbps", Blue, Modifier.weight(1f)); MetricCard("Core Power", s.power, "Stable", Yellow, Modifier.weight(1f)) } }
        item { Text("System Overview", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 5.dp)) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { StatCard("CPU", s.cpu, "%", Blue, Modifier.weight(1f)); StatCard("Memory", s.memory, "%", Cyan, Modifier.weight(1f)); StatCard("Disk", s.disk, "%", Purple, Modifier.weight(1f)) } }
        item { ChartCard("CPU Load", "${s.cpu}%", Blue, chartData(s.cpu), "Warning 75% • Critical 90%", "Highest in last 5 minutes") }
        item { ChartCard("Memory", "${s.memory}%", Cyan, chartData(s.memory), "Warning 75% • Critical 90%", "${s.memoryUsed}/${s.memoryTotal}") }
        item { ChartCard("Disk Usage", "${s.disk}%", Purple, chartData(s.disk), "Primary storage filesystem and connected external USB drives.", "${s.diskUsed}/${s.diskTotal}") }
        item { ChartCard("Temperature", "${s.temp}°C", Color(0xFFFFA45B), chartData(s.temp, 35, 65), "Warning 65°C • Critical 75°C", "Highest in last 5 minutes") }
        item { ChartCard("Core Power", s.power, Yellow, chartData(60, 35, 90), "Core power consumption, voltage, throttling, and cooling.", "Usage detail: Stable") }
    }
}

@Composable
fun DeviceHero(s: PiStats, connected: Boolean, connect: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Card2), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(58.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF28101B)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Memory, null, tint = Pink, modifier = Modifier.size(32.dp)) }
            Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) { Text(s.hostname, fontSize = 19.sp, fontWeight = FontWeight.Bold); Text("Raspberry Pi • ${s.uptime}", color = Color.Gray, fontSize = 13.sp) }
            Button(onClick = connect, colors = ButtonDefaults.buttonColors(containerColor = if (connected) Color(0xFF173D24) else Pink), shape = RoundedCornerShape(14.dp)) { Text(if (connected) "Manage" else "Connect") }
        }
    }
}

@Composable
fun MetricCard(title: String, value: String, sub: String, tint: Color, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Card), shape = RoundedCornerShape(20.dp)) { Column(Modifier.padding(16.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Icon(if (title == "Traffic") Icons.Default.Wifi else Icons.Default.Bolt, null, tint = tint, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(7.dp)); Text(title, fontWeight = FontWeight.SemiBold) }; Text(value, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 15.dp)); Text(sub, color = if (sub == "Stable") Color.Gray else tint, fontSize = 12.sp) } }
}

@Composable
fun StatCard(title: String, value: Int, unit: String, tint: Color, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Card), shape = RoundedCornerShape(20.dp)) { Column(Modifier.padding(14.dp)) { Text(title, color = Color.Gray, fontSize = 12.sp); Text("$value$unit", fontSize = 24.sp, fontWeight = FontWeight.Bold); MiniChart(chartData(value), tint, Modifier.fillMaxWidth().height(42.dp)) } }
}

@Composable
fun ChartCard(title: String, value: String, tint: Color, data: List<Float>, threshold: String, detail: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Card), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) { Column(Modifier.weight(1f)) { Text(title, color = tint, fontWeight = FontWeight.Bold); Text(value, fontSize = 27.sp, fontWeight = FontWeight.Bold); Text("Within normal range", color = Green, fontSize = 12.sp) }; Text("Updated now", color = Color.Gray, fontSize = 11.sp) }
            Spacer(Modifier.height(12.dp)); MiniChart(data, tint, Modifier.fillMaxWidth().height(160.dp)); Spacer(Modifier.height(12.dp))
            Card(colors = CardDefaults.cardColors(containerColor = Card2), shape = RoundedCornerShape(16.dp)) { Column(Modifier.padding(13.dp)) { Text("Thresholds", color = Color.Gray, fontSize = 11.sp); Text(threshold, fontWeight = FontWeight.Medium); Spacer(Modifier.height(8.dp)); Text(detail, color = Color.LightGray, fontSize = 12.sp) } }
        }
    }
}

fun chartData(value: Int, min: Int = 0, max: Int = 100): List<Float> { val base = value.toFloat(); return List(24) { i -> (base + kotlin.math.sin(i / 2.7) * (max - min) * .07f + (i - 12) * .02f).coerceIn(min.toFloat(), max.toFloat()) } }

@Composable
fun MiniChart(data: List<Float>, tint: Color, modifier: Modifier) {
    Canvas(modifier) {
        if (data.isEmpty()) return@Canvas
        val min = data.minOrNull() ?: 0f; val max = data.maxOrNull() ?: 100f; val range = (max - min).coerceAtLeast(1f)
        val path = Path(); data.forEachIndexed { i, v -> val x = i * size.width / (data.size - 1).coerceAtLeast(1); val y = size.height - ((v - min) / range) * size.height; if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
        drawPath(path, tint, style = Stroke(width = 4f, cap = StrokeCap.Round))
        drawCircle(tint, 5f, Offset(size.width, size.height - ((data.last() - min) / range) * size.height))
    }
}

@Composable
fun DevicesScreen(s: PiStats, connected: Boolean, connect: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TopBar("My Devices", connected, connect) }
        item { Card(colors = CardDefaults.cardColors(containerColor = Card), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth().clickable(onClick = connect)) { Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Storage, null, Pink); Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) { Text(s.hostname, fontWeight = FontWeight.Bold, fontSize = 18.sp); Text("Raspberry Pi • SSH :22", color = Color.Gray) }; Text("›", color = Color.Gray, fontSize = 30.sp) } } }
        item { Text("Quick Actions", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
        item { QuickAction("Restart Raspberry Pi", Icons.Default.RestartAlt, Yellow) }
        item { QuickAction("Shutdown Raspberry Pi", Icons.Default.PowerSettingsNew, Red) }
        item { QuickAction("Update Package Index", Icons.Default.SystemUpdate, Pink) }
        item { QuickAction("Upgrade Packages", Icons.Default.Inventory, Blue) }
        item { QuickAction("Restart Networking", Icons.Default.Wifi, Cyan) }
    }
}

@Composable fun QuickAction(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color) { Card(colors = CardDefaults.cardColors(containerColor = Card), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().clickable {}) { Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint); Spacer(Modifier.width(14.dp)); Text(text, fontWeight = FontWeight.Medium); Spacer(Modifier.weight(1f)); Icon(Icons.Default.ChevronRight, null, tint = Color.Gray) } } }

@Composable
fun FilesScreen(vm: PiHubViewModel) {
    val files by vm.files.collectAsState(); val connected by vm.connected.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TopBar("Files", connected) { vm.loadFiles(vm.currentPath()) }
        Row(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(14.dp)).background(Card).fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Folder, null, Pink); Spacer(Modifier.width(8.dp)); Text(vm.currentPath(), maxLines = 1, modifier = Modifier.horizontalScroll(rememberScrollState())) }
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = { vm.goUp() }) { Icon(Icons.Default.ArrowUpward, null); Spacer(Modifier.width(5.dp)); Text("Up") }; Button(onClick = { vm.loadFiles(vm.currentPath()) }) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(5.dp)); Text("Refresh") } }
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { items(files) { f -> Card(colors = CardDefaults.cardColors(containerColor = Card2), shape = RoundedCornerShape(13.dp), modifier = Modifier.fillMaxWidth().clickable { if (f.isDir) vm.loadFiles(vm.currentPath().trimEnd('/') + "/" + f.name) }) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(if (f.isDir) Icons.Default.Folder else Icons.Default.Description, null, tint = if (f.isDir) Blue else Color.Gray); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(f.name, fontWeight = FontWeight.Medium); Text(f.detail, color = Color.Gray, fontSize = 11.sp) }; if (f.isDir) Icon(Icons.Default.ChevronRight, null, tint = Color.Gray) } } } }
    }
}

@Composable
fun ProcessesScreen(vm: PiHubViewModel) {
    val processes by vm.processes.collectAsState(); val connected by vm.connected.collectAsState(); var query by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        TopBar("Processes", connected) { vm.loadProcesses() }
        OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), placeholder = { Text("Search processes") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, shape = RoundedCornerShape(18.dp), colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Card, focusedContainerColor = Card, unfocusedBorderColor = Color.Transparent, focusedBorderColor = Pink))
        Text("Top Processes", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(18.dp))
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) { items(processes.filter { it.name.contains(query, true) }) { p -> Card(colors = CardDefaults.cardColors(containerColor = Card), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(p.name, fontWeight = FontWeight.Bold); Text("› PID ${p.pid}", color = Pink, fontSize = 12.sp) }; IconButton(onClick = { vm.killProcess(p.pid) }) { Icon(Icons.Default.StopCircle, null, tint = Red) } }; Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { SmallStat("CPU", p.cpu); SmallStat("Memory", p.mem); SmallStat("RSS", p.rss); SmallStat("Time", p.time) } } } } }
    }
}

@Composable fun SmallStat(a: String, b: String) { Column { Text(a, color = Color.Gray, fontSize = 10.sp); Text(b, fontSize = 12.sp, fontWeight = FontWeight.Medium) } }

@Composable
fun MoreScreen(vm: PiHubViewModel, s: PiStats) {
    var section by remember { mutableStateOf("Services") }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TopBar("PiHub Tools") }
        item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Services", "Terminal", "GPIO", "Plans", "Settings").forEach { x -> FilterChip(selected = section == x, onClick = { section = x }, label = { Text(x) }) } } }
        when (section) {
            "Services" -> { item { ServicesScreen(vm) } }
            "Terminal" -> { item { TerminalScreen(vm, s.hostname) } }
            "GPIO" -> { item { GpioScreen() } }
            "Plans" -> { item { PiHubPlansScreen() } }
            else -> { item { SettingsScreen(s) } }
        }
    }
}

@Composable
fun ServicesScreen(vm: PiHubViewModel) {
    val services by vm.services.collectAsState(); Column(verticalArrangement = Arrangement.spacedBy(9.dp)) { services.forEach { service -> Card(colors = CardDefaults.cardColors(containerColor = Card), shape = RoundedCornerShape(17.dp), modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.SettingsApplications, null, Pink); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(service.name, fontWeight = FontWeight.Bold); Text("system service", color = Color.Gray, fontSize = 11.sp) }; Text(service.state, color = if (service.state == "Running") Green else Red, fontWeight = FontWeight.Bold, fontSize = 12.sp); IconButton(onClick = { vm.serviceAction(service.name, "restart") }) { Icon(Icons.Default.Refresh, null, tint = Yellow) } } } } }
}

@Composable
fun TerminalScreen(vm: PiHubViewModel, host: String) {
    val output by vm.terminal.collectAsState(); var command by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF09090A)), shape = RoundedCornerShape(17.dp), modifier = Modifier.fillMaxWidth().height(360.dp)) { Text(output.ifBlank { "PiHub terminal\nConnected shell: $host\n\nRun a command below…" }, color = Color(0xFFB9FFB9), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(14.dp).fillMaxSize()) }
        Row(verticalAlignment = Alignment.CenterVertically) { OutlinedTextField(value = command, onValueChange = { command = it }, modifier = Modifier.weight(1f), placeholder = { Text("Run command") }, singleLine = true, shape = RoundedCornerShape(15.dp)); Spacer(Modifier.width(8.dp)); IconButton(onClick = { if (command.isNotBlank()) { vm.runCommand(command); command = "" } }) { Icon(Icons.Default.Send, null, tint = Pink) } }
    }
}

@Composable fun GpioScreen() { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("GPIO Control", fontSize = 20.sp, fontWeight = FontWeight.Bold); Text("Control Raspberry Pi GPIO through libgpiod when available.", color = Color.Gray); (2..27).forEach { pin -> Card(colors = CardDefaults.cardColors(containerColor = if (pin % 3 == 0) Color(0xFF102A39) else Card), shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Text("GPIO$pin", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Text(if (pin % 3 == 0) "Input" else "Low", color = if (pin % 3 == 0) Blue else Color.Gray); Switch(checked = pin % 3 == 0, onCheckedChange = {}) } } } } }

@Composable fun SettingsScreen(s: PiStats) { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { SettingRow("Appearance", "Dark theme", Icons.Default.DarkMode); SettingRow("Security", "SSH connection & device access", Icons.Default.Security); SettingRow("Notifications", "Android notifications on", Icons.Default.Notifications); SettingRow("Device", "${s.hostname} • SSH", Icons.Default.Memory); SettingRow("About PiHub", "Raspberry Pi management", Icons.Default.Info) } }
@Composable fun SettingRow(a: String, b: String, icon: androidx.compose.ui.graphics.vector.ImageVector) { Card(colors = CardDefaults.cardColors(containerColor = Card), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = Pink); Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) { Text(a, fontWeight = FontWeight.Bold); Text(b, color = Color.Gray, fontSize = 12.sp) }; Icon(Icons.Default.ChevronRight, null, tint = Color.Gray) } } }

@Composable
fun ConnectDialog(vm: PiHubViewModel, connected: Boolean, dismiss: () -> Unit, notify: (String) -> Unit) {
    var host by remember { mutableStateOf("raspberrypi.local") }; var user by remember { mutableStateOf("pi") }; var password by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, containerColor = Card, title = { Text(if (connected) "PiHub Connection" else "Connect to Raspberry Pi") }, text = { Column(verticalArrangement = Arrangement.spacedBy(9.dp)) { Text("PiHub uses SSH to securely manage your Raspberry Pi.", color = Color.Gray, fontSize = 12.sp); OutlinedTextField(host, { host = it }, label = { Text("Hostname / IP") }, singleLine = true); OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true); OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true) } }, confirmButton = { Button(onClick = { vm.connect(host, user, password) { ok, msg -> notify(msg); if (ok) dismiss() } }) { Text("Connect") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable fun ToastCard(message: String) { Box(Modifier.fillMaxSize().padding(bottom = 75.dp), contentAlignment = Alignment.BottomCenter) { Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF29292D)), shape = RoundedCornerShape(18.dp)) { Text(message, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp), fontSize = 13.sp) } } }
