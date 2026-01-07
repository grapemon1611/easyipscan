package com.almostbrilliantideas.easyipscanner

import android.content.Context
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.almostbrilliantideas.easyipscanner.ui.theme.EasyIPScannerTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EasyIPScannerTheme {
                AppEntryPoint()
            }
        }
    }
}

@Composable
fun AppEntryPoint() {
    val context = LocalContext.current
    var userDismissedWarning by remember { mutableStateOf(false) }

    // Check WiFi status on every launch
    val hasWifiConnection = remember { NetworkConnectivity.hasValidLanConnection(context) }

    // Show warning only if no WiFi AND user hasn't dismissed it this session
    if (!hasWifiConnection && !userDismissedWarning) {
        WifiWarningScreen(
            onOpenSettings = {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
            },
            onDismiss = {
                userDismissedWarning = true
            }
        )
    } else {
        AppRoot()
    }
}

@Composable
fun AppRoot() {
    var tabIndex by remember { mutableStateOf(0) }
    var toolsClickCounter by remember { mutableStateOf(0) }
    val tabs = listOf("Scan Network", "Tools")

    val exo2FontFamily = FontFamily(
        Font(R.font.exo2_semibold, FontWeight.SemiBold)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = androidx.compose.ui.graphics.Color(0xFF000099)
        ) {
            Text(
                text = "EasyIP Scan™",
                style = MaterialTheme.typography.headlineMedium,
                fontFamily = exo2FontFamily,
                fontWeight = FontWeight.SemiBold,
                color = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.padding(16.dp)
            )
        }

        TabRow(selectedTabIndex = tabIndex) {
            tabs.forEachIndexed { i, t ->
                Tab(
                    selected = tabIndex == i,
                    onClick = {
                        tabIndex = i
                        if (i == 1) {
                            toolsClickCounter++
                        }
                    },
                    text = { Text(t) }
                )
            }
        }
        when (tabIndex) {
            0 -> ScanTab()
            else -> ToolsTab(resetTrigger = toolsClickCounter)
        }
    }
}

@Composable
fun ToolsTab(resetTrigger: Int = 0) {
    var selectedTool by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(resetTrigger) {
        selectedTool = null
    }

    when (selectedTool) {
        "SpeedTest" -> SpeedTestTab()
        "Ping" -> PingTab()
        "PortScanner" -> PortScannerTab()
        "WiFi" -> WiFiTab()
        else -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Text(
                    text = "Network Tools",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                ToolMenuItem(
                    title = "Speed Test",
                    description = "Test LAN and WAN network speeds",
                    enabled = true,
                    onClick = { selectedTool = "SpeedTest" }
                )

                ToolMenuItem(
                    title = "Ping",
                    description = "Test connectivity to a specific IP address",
                    enabled = true,
                    onClick = { selectedTool = "Ping" }
                )

                ToolMenuItem(
                    title = "Port Scanner",
                    description = "Scan network for open ports",
                    enabled = true,
                    onClick = { selectedTool = "PortScanner" }
                )

                ToolMenuItem(
                    title = "WiFi Diagnostics",
                    description = "View WiFi connection details and signal strength",
                    enabled = true,
                    onClick = { selectedTool = "WiFi" }
                )
            }
        }
    }
}

@Composable
fun ToolMenuItem(
    title: String,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        onClick = onClick,
        enabled = enabled,
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (enabled) 2.dp else 0.dp
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            if (!enabled) {
                Text(
                    text = "Coming Soon",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
fun SpeedTestTab() {
    val context = LocalContext.current
    var lanSpeed by remember { mutableStateOf<Double?>(null) }
    var wanSpeed by remember { mutableStateOf<Double?>(null) }
    var testingLan by remember { mutableStateOf(false) }
    var testingWan by remember { mutableStateOf(false) }
    var liveOutput by remember { mutableStateOf("") }
    var detailedResults by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Speed Test", style = MaterialTheme.typography.titleLarge)

        Text(
            text = "Test your network performance",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                if (!testingLan && !testingWan) {
                    testingLan = true
                    liveOutput = ""
                    detailedResults = ""
                    lanSpeed = null

                    scope.launch(Dispatchers.IO) {
                        val result = testLanSpeedWithLiveOutput(context) { update ->
                            scope.launch(Dispatchers.Main) {
                                liveOutput = update
                                scope.launch {
                                    scrollState.animateScrollTo(scrollState.maxValue)
                                }
                            }
                        }

                        withContext(Dispatchers.Main) {
                            if (result != null) {
                                lanSpeed = result.speedMbps
                                detailedResults = result.detailedOutput
                                liveOutput = ""
                            } else {
                                detailedResults = "\n❌ Test failed - could not connect to gateway"
                            }
                            testingLan = false
                        }
                    }
                }
            },
            enabled = !testingLan && !testingWan
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "LAN Speed (Device → Router)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (testingLan) "Testing..." else "Tap to test local network",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Icon(
                        imageVector = if (testingLan) Icons.Default.Refresh else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = androidx.compose.ui.graphics.Color(0xFF000099)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (lanSpeed != null) {
                    Text(
                        text = "${String.format("%.1f", lanSpeed)} Mbps",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = androidx.compose.ui.graphics.Color(0xFF000099)
                    )
                    Text(
                        text = getRating(lanSpeed!!),
                        style = MaterialTheme.typography.bodyMedium,
                        color = getRatingColor(lanSpeed!!)
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                if (!testingLan && !testingWan) {
                    testingWan = true
                    liveOutput = ""
                    detailedResults = ""
                    wanSpeed = null

                    scope.launch(Dispatchers.IO) {
                        val result = testWanSpeedWithLiveOutput { update ->
                            scope.launch(Dispatchers.Main) {
                                liveOutput = update
                                scope.launch {
                                    scrollState.animateScrollTo(scrollState.maxValue)
                                }
                            }
                        }

                        withContext(Dispatchers.Main) {
                            if (result != null) {
                                wanSpeed = result.speedMbps
                                detailedResults = result.detailedOutput
                                liveOutput = ""
                            } else {
                                detailedResults = "\n❌ Test failed - could not connect to test server"
                            }
                            testingWan = false
                        }
                    }
                }
            },
            enabled = !testingLan && !testingWan
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "WAN Speed (Device → Internet)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (testingWan) "Testing..." else "Tap to test internet speed",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Icon(
                        imageVector = if (testingWan) Icons.Default.Refresh else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = androidx.compose.ui.graphics.Color(0xFF000099)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (wanSpeed != null) {
                    Text(
                        text = "${String.format("%.1f", wanSpeed)} Mbps",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = androidx.compose.ui.graphics.Color(0xFF000099)
                    )
                    Text(
                        text = getWanRating(wanSpeed!!),
                        style = MaterialTheme.typography.bodyMedium,
                        color = getWanRatingColor(wanSpeed!!)
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                if (!testingLan && !testingWan) {
                    testingLan = true
                    testingWan = true
                    liveOutput = ""
                    detailedResults = ""
                    lanSpeed = null
                    wanSpeed = null

                    scope.launch(Dispatchers.IO) {
                        withContext(Dispatchers.Main) {
                            liveOutput = "═══════════════════════════════\n" +
                                    "STEP 1/2: LAN SPEED TEST\n" +
                                    "═══════════════════════════════\n\n"
                        }

                        val lanResult = testLanSpeedWithLiveOutput(context) { update ->
                            scope.launch(Dispatchers.Main) {
                                liveOutput = "═══════════════════════════════\n" +
                                        "STEP 1/2: LAN SPEED TEST\n" +
                                        "═══════════════════════════════\n\n" +
                                        update
                                scope.launch {
                                    scrollState.animateScrollTo(scrollState.maxValue)
                                }
                            }
                        }

                        withContext(Dispatchers.Main) {
                            if (lanResult != null) {
                                lanSpeed = lanResult.speedMbps
                            }
                            testingLan = false
                        }

                        delay(1000)

                        val wanResult = testWanSpeedWithLiveOutput { update ->
                            scope.launch(Dispatchers.Main) {
                                liveOutput = "═══════════════════════════════\n" +
                                        "STEP 2/2: WAN SPEED TEST\n" +
                                        "═══════════════════════════════\n\n" +
                                        update
                                scope.launch {
                                    scrollState.animateScrollTo(scrollState.maxValue)
                                }
                            }
                        }

                        withContext(Dispatchers.Main) {
                            if (wanResult != null) {
                                wanSpeed = wanResult.speedMbps
                            }
                            testingWan = false

                            if (lanResult != null && wanResult != null) {
                                detailedResults = buildComparisonDiagnosis(lanResult.speedMbps, wanResult.speedMbps)
                                liveOutput = ""
                            } else {
                                detailedResults = "\n❌ Test incomplete - one or both tests failed"
                                liveOutput = ""
                            }
                        }
                    }
                }
            },
            enabled = !testingLan && !testingWan,
            colors = CardDefaults.cardColors(
                containerColor = androidx.compose.ui.graphics.Color(0xFF000099)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Complete Test (LAN + WAN)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = androidx.compose.ui.graphics.Color.White
                        )
                        Text(
                            text = if (testingLan || testingWan) "Testing..." else "Tap for full diagnostic",
                            style = MaterialTheme.typography.bodySmall,
                            color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f)
                        )
                    }

                    Icon(
                        imageVector = if (testingLan || testingWan) Icons.Default.Refresh else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = androidx.compose.ui.graphics.Color.White
                    )
                }
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(12.dp)
            ) {
                Text(
                    text = if (liveOutput.isEmpty() && detailedResults.isEmpty())
                        "Tap a card above to begin test"
                    else
                        liveOutput + detailedResults,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (liveOutput.isEmpty() && detailedResults.isEmpty())
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else
                        MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
fun PingTab() {
    var ip by remember { mutableStateOf(TextFieldValue("192.168.1.1")) }
    var output by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var pingCount by remember { mutableStateOf(4) }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    var pingJob by remember { mutableStateOf<Job?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Ping", style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = ip,
            onValueChange = { ip = it },
            label = { Text("IP address") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = !working
        )

        Text(
            text = "Ping Count:",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp)
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            listOf(4, 10, 20).forEach { count ->
                Button(
                    onClick = {
                        pingCount = count
                        startPing(count, ip.text, scope, scrollState,
                            { working = true },
                            { working = false },
                            { text -> output += text + "\n" },
                            { output = "" },
                            { job -> pingJob = job })
                    },
                    enabled = !working,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(count.toString())
                }
            }
        }

        Button(
            onClick = {
                pingCount = -1
                startPing(-1, ip.text, scope, scrollState,
                    { working = true },
                    { working = false },
                    { text -> output += text + "\n" },
                    { output = "" },
                    { job -> pingJob = job })
            },
            enabled = !working,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary
            ),
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium
        ) {
            Text("Continuous")
        }

        if (working) {
            Button(
                onClick = {
                    pingJob?.cancel()
                    working = false
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Stop")
            }
        }

        Card(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(12.dp)
            ) {
                Text(
                    text = output.ifEmpty { "Select ping count and click Start Ping" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = if (output.isEmpty())
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else
                        MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

fun startPing(
    count: Int,
    ipAddress: String,
    scope: CoroutineScope,
    scrollState: ScrollState,
    onStart: () -> Unit,
    onFinish: () -> Unit,
    appendOutput: (String) -> Unit,
    clearOutput: () -> Unit,
    setJob: (Job) -> Unit
) {
    onStart()
    clearOutput()

    val job = scope.launch(Dispatchers.IO) {
        suspend fun addLine(text: String) {
            scope.launch(Dispatchers.Main) {
                appendOutput(text)
                scrollState.animateScrollTo(scrollState.maxValue)
            }
        }

        val targetCount = if (count == -1) Int.MAX_VALUE else count
        addLine("Pinging $ipAddress${if (count == -1) " (continuous)" else ""}...\n")

        var successCount = 0
        var attemptCount = 0

        try {
            for (i in 1..targetCount) {
                if (!isActive) break

                attemptCount++
                val startTime = System.currentTimeMillis()
                val result = systemPingSingle(ipAddress, 1000)
                val elapsed = System.currentTimeMillis() - startTime

                if (result != null) {
                    successCount++
                    addLine("Reply from $ipAddress: time=${elapsed}ms")
                } else {
                    addLine("Request timed out.")
                }

                if (i < targetCount) delay(1000)
            }
        } catch (e: CancellationException) {
            addLine("\n[Stopped by user]")
        }

        if (attemptCount > 0) {
            addLine("\n--- Ping statistics ---")
            addLine("$attemptCount packets transmitted, $successCount received, ${if (attemptCount > 0) ((attemptCount-successCount)*100/attemptCount) else 0}% packet loss")
        }

        if (successCount > 0 && isActive) {
            addLine("\n=== Discovering device info ===")
            val names = discoverAllNames(ipAddress)
            val bestName = names.getBestName()
            addLine("Device Name: ${bestName ?: "(unable to resolve)"}")

            if (names.ssdp != null) addLine("  SSDP: ${names.ssdp}")
            if (names.mdns != null) addLine("  mDNS: ${names.mdns}")
            if (names.netbios != null) addLine("  NetBIOS: ${names.netbios}")
            if (names.dns != null) addLine("  DNS: ${names.dns}")
        } else if (successCount == 0 && isActive) {
            addLine("\nICMP failed, trying TCP probes...")
            if (tcpProbe(ipAddress, 80, 300)) {
                addLine("✓ TCP probe to port 80 succeeded")
            } else if (tcpProbe(ipAddress, 443, 300)) {
                addLine("✓ TCP probe to port 443 succeeded")
            } else {
                addLine("✗ No response on common ports")
            }
        }

        withContext(Dispatchers.Main) {
            onFinish()
        }
    }

    setJob(job)
}

@Composable
fun ScanTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Network state tracking
    var networkState by remember { mutableStateOf(NetworkConnectivity.getNetworkState(context)) }
    var hasValidLan by remember { mutableStateOf(NetworkConnectivity.hasValidLanConnection(context)) }

    // Network change detection state
    val appPreferences = remember { AppPreferences(context) }
    var showNetworkChangeDialog by remember { mutableStateOf(false) }
    var networkChangeResult by remember { mutableStateOf<NetworkChangeResult?>(null) }
    var hasCheckedNetworkOnResume by remember { mutableStateOf(false) }

    // Refresh network state periodically
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            networkState = NetworkConnectivity.getNetworkState(context)
            hasValidLan = NetworkConnectivity.hasValidLanConnection(context)
        }
    }

    // Check for network change on app resume/foreground
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    val lastScanned = appPreferences.getLastScannedNetworkSync()
                    if (lastScanned != null) {
                        val result = NetworkConnectivity.checkNetworkChanged(context, lastScanned)
                        if (result.hasChanged && hasValidLan) {
                            networkChangeResult = result
                            showNetworkChangeDialog = true
                        }
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Also check on first composition if we haven't yet
    LaunchedEffect(hasValidLan) {
        if (!hasCheckedNetworkOnResume && hasValidLan) {
            hasCheckedNetworkOnResume = true
            val lastScanned = appPreferences.getLastScannedNetworkSync()
            if (lastScanned != null) {
                val result = NetworkConnectivity.checkNetworkChanged(context, lastScanned)
                if (result.hasChanged) {
                    networkChangeResult = result
                    showNetworkChangeDialog = true
                }
            }
        }
    }

    val mdns = remember { MdnsActiveCache(context) }
    val ssdp = remember { SsdpDiscovery(context) }
    val db = remember { DeviceDatabase(context) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            println("Starting mDNS...")
            mdns.start()

            println("Starting SSDP...")
            ssdp.start()

            println("Discovery services started and running in background")
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            mdns.stop()
            ssdp.stop()
        }
    }

    var cidr by remember { mutableStateOf(TextFieldValue(detectBestCidr(context) ?: "192.168.1.0/24")) }
    var scanning by remember { mutableStateOf(false) }
    var showAllHistorical by remember { mutableStateOf(false) }
    var categorizedDevices by remember { mutableStateOf<Map<DeviceState, List<StoredDevice>>>(emptyMap()) }
    var mdnsCount by remember { mutableStateOf(0) }
    var ssdpCount by remember { mutableStateOf(0) }
    var devicesFoundCount by remember { mutableStateOf(0) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var deviceToRename by remember { mutableStateOf<StoredDevice?>(null) }
    var renameText by remember { mutableStateOf(TextFieldValue("")) }
    val jobRef = remember { mutableStateOf<Job?>(null) }

    // Wake lock to keep screen on during scan
    val wakeLock = remember {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        powerManager.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "EasyIPScan:NetworkScan")
    }

    DisposableEffect(wakeLock) {
        onDispose {
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
        }
    }

    LaunchedEffect(scanning) {
        while (scanning) {
            delay(200)
            mdnsCount = mdns.names.size
            ssdpCount = ssdp.names.size
        }
        mdnsCount = mdns.names.size
        ssdpCount = ssdp.names.size
    }

    suspend fun refreshDeviceList() {
        withContext(Dispatchers.IO) {
            val allDevices = db.getAllDevices()
            val scanTime = System.currentTimeMillis()
            val categorized = categorizeDevices(allDevices, scanTime, if (showAllHistorical) Int.MAX_VALUE else 7)
            withContext(Dispatchers.Main) {
                categorizedDevices = categorized
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshDeviceList()
    }

    val concurrency = 80
    val timeoutMs = 500

    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Scan Network", style = MaterialTheme.typography.titleLarge)

        Text(
            text = "mDNS: $mdnsCount | SSDP: $ssdpCount | Found: $devicesFoundCount",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )

        OutlinedTextField(
            value = cidr,
            onValueChange = { cidr = it },
            label = { Text("CIDR (e.g., 10.0.0.0/24)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !scanning,
                onClick = {
                    scanning = true
                    devicesFoundCount = 0
                    categorizedDevices = emptyMap()

                    // Acquire wake lock to keep screen on
                    if (!wakeLock.isHeld) {
                        wakeLock.acquire(10 * 60 * 1000L) // 10 minute timeout
                    }

                    val typed = cidr.text.trim()
                    val detected = detectBestCidr(context)
                    val effectiveCidr = normalizeToNetworkCidr(
                        candidate = typed.ifEmpty { detected ?: "" },
                        fallback = detected ?: "192.168.1.0/24"
                    )
                    cidr = TextFieldValue(effectiveCidr)

                    jobRef.value = scope.launch(Dispatchers.IO) {
                        scanCidr(effectiveCidr, concurrency, timeoutMs, mdns, ssdp, db) { result ->
                            if (result.status != "No response") {
                                scope.launch(Dispatchers.Main) {
                                    devicesFoundCount++
                                }
                            }
                        }

                        // Save network info after scan completes
                        val currentNetwork = NetworkConnectivity.getCurrentNetwork(context)
                        appPreferences.saveScannedNetwork(
                            ssid = currentNetwork.ssid,
                            gatewayIp = currentNetwork.gatewayIp
                        )

                        refreshDeviceList()

                        withContext(Dispatchers.Main) {
                            scanning = false
                            // Release wake lock when scan completes
                            if (wakeLock.isHeld) {
                                wakeLock.release()
                            }
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = androidx.compose.ui.graphics.Color(0xFF000099)
                )
            ) {
                Text("Start Scan")
            }

            Button(
                enabled = scanning,
                onClick = {
                    jobRef.value?.cancel()
                    scanning = false
                    // Release wake lock when scan is stopped
                    if (wakeLock.isHeld) {
                        wakeLock.release()
                    }
                }
            ) {
                Text("Stop")
            }

            Button(
                enabled = !scanning,
                onClick = {
                    showClearConfirmDialog = true
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Clear All")
            }
        }

        if (showClearConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showClearConfirmDialog = false },
                title = { Text("Clear Database?") },
                text = { Text("This will permanently delete all saved devices from all networks. This action cannot be undone.") },
                confirmButton = {
                    Button(
                        onClick = {
                            showClearConfirmDialog = false
                            scope.launch(Dispatchers.IO) {
                                db.clearAllDevices()
                                appPreferences.clearLastScannedNetwork()
                                refreshDeviceList()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Clear")
                    }
                },
                dismissButton = {
                    Button(
                        onClick = { showClearConfirmDialog = false }
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Network change detection dialog
        if (showNetworkChangeDialog && networkChangeResult != null) {
            val result = networkChangeResult!!
            val currentNetworkName = result.currentNetwork.ssid ?: result.currentNetwork.gatewayIp ?: "Unknown Network"
            val previousNetworkName = result.previousSsid ?: result.previousGateway ?: "Previous Network"

            AlertDialog(
                onDismissRequest = {
                    showNetworkChangeDialog = false
                    networkChangeResult = null
                },
                title = {
                    Text(
                        "Network Changed",
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "You've connected to a different network.",
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    "Current Network:",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                )
                                Text(
                                    currentNetworkName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                if (result.currentNetwork.gatewayIp != null && result.currentNetwork.ssid != null) {
                                    Text(
                                        "Gateway: ${result.currentNetwork.gatewayIp}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }

                        Text(
                            "The saved device list is from \"$previousNetworkName\". Would you like to clear it and scan this network?",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showNetworkChangeDialog = false
                            networkChangeResult = null

                            // Clear device list and start new scan
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    db.clearAllDevices()
                                    appPreferences.clearLastScannedNetwork()
                                }
                                refreshDeviceList()
                            }

                            // Trigger a scan
                            scanning = true
                            devicesFoundCount = 0
                            categorizedDevices = emptyMap()

                            if (!wakeLock.isHeld) {
                                wakeLock.acquire(10 * 60 * 1000L)
                            }

                            val detected = detectBestCidr(context)
                            val effectiveCidr = normalizeToNetworkCidr(
                                candidate = detected ?: "",
                                fallback = "192.168.1.0/24"
                            )
                            cidr = TextFieldValue(effectiveCidr)

                            jobRef.value = scope.launch(Dispatchers.IO) {
                                scanCidr(effectiveCidr, concurrency, timeoutMs, mdns, ssdp, db) { scanResult ->
                                    if (scanResult.status != "No response") {
                                        scope.launch(Dispatchers.Main) {
                                            devicesFoundCount++
                                        }
                                    }
                                }

                                // Save network info after scan
                                val currentNetwork = NetworkConnectivity.getCurrentNetwork(context)
                                appPreferences.saveScannedNetwork(
                                    ssid = currentNetwork.ssid,
                                    gatewayIp = currentNetwork.gatewayIp
                                )

                                refreshDeviceList()

                                withContext(Dispatchers.Main) {
                                    scanning = false
                                    if (wakeLock.isHeld) {
                                        wakeLock.release()
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = androidx.compose.ui.graphics.Color(0xFF000099)
                        )
                    ) {
                        Text("Scan New Network")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showNetworkChangeDialog = false
                            networkChangeResult = null
                        }
                    ) {
                        Text("Keep List")
                    }
                }
            )
        }

        if (showRenameDialog && deviceToRename != null) {
            AlertDialog(
                onDismissRequest = {
                    showRenameDialog = false
                    deviceToRename = null
                },
                title = { Text("Rename Device") },
                text = {
                    Column {
                        Text(
                            "IP: ${deviceToRename!!.ip}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = renameText,
                            onValueChange = { renameText = it },
                            label = { Text("Custom Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (deviceToRename!!.customName != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Leave empty to use discovered name",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val newName = renameText.text.trim().ifBlank { null }
                            scope.launch(Dispatchers.IO) {
                                db.setCustomName(deviceToRename!!.ip, newName)
                                refreshDeviceList()
                            }
                            showRenameDialog = false
                            deviceToRename = null
                        }
                    ) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    Button(
                        onClick = {
                            showRenameDialog = false
                            deviceToRename = null
                        }
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }

        val totalDevices = categorizedDevices.values.sumOf { it.size }
        val newCount = categorizedDevices[DeviceState.NEW]?.size ?: 0
        val backOnlineCount = categorizedDevices[DeviceState.BACK_ONLINE]?.size ?: 0
        val stillOnlineCount = categorizedDevices[DeviceState.STILL_ONLINE]?.size ?: 0
        val wentOfflineCount = categorizedDevices[DeviceState.WENT_OFFLINE]?.size ?: 0
        val historicalCount = categorizedDevices[DeviceState.HISTORICAL]?.size ?: 0

        Text(
            "Devices: $totalDevices total (New: $newCount, Back: $backOnlineCount, Still: $stillOnlineCount, Offline: $wentOfflineCount, Historical: $historicalCount)",
            style = MaterialTheme.typography.titleMedium
        )

        val allDevicesWithState = categorizedDevices.flatMap { (state, devices) ->
            devices.map { device -> device to state }
        }.sortedBy { (device, _) -> ipToInt(device.ip) ?: 0 }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (scanning && allDevicesWithState.isEmpty()) {
                item {
                    var dotCount by remember { mutableStateOf(1) }
                    LaunchedEffect(Unit) {
                        while (true) {
                            delay(400)
                            dotCount = if (dotCount >= 8) 1 else dotCount + 1
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "SCANNING",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = ".".repeat(dotCount).padEnd(8, ' '),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(96.dp)
                            )
                        }
                    }
                }
            }

            items(allDevicesWithState) { (device, state) ->
                val (backgroundColor, textColor) = when (state) {
                    DeviceState.NEW ->
                        androidx.compose.ui.graphics.Color(0xFFCFE8FF) to androidx.compose.ui.graphics.Color(0xFF0B2C4A)
                    DeviceState.BACK_ONLINE ->
                        androidx.compose.ui.graphics.Color(0xFFD7F5DD) to androidx.compose.ui.graphics.Color(0xFF1B5E20)
                    DeviceState.STILL_ONLINE ->
                        androidx.compose.ui.graphics.Color(0xFFF5F5F5) to androidx.compose.ui.graphics.Color(0xFF424242)
                    DeviceState.WENT_OFFLINE ->
                        androidx.compose.ui.graphics.Color(0xFFFDE2E2) to androidx.compose.ui.graphics.Color(0xFFB71C1C)
                    DeviceState.HISTORICAL ->
                        androidx.compose.ui.graphics.Color(0xFFEEF2F6) to androidx.compose.ui.graphics.Color(0xFF6B7280)
                }

                DeviceCard(
                    device = device,
                    deviceState = state,
                    backgroundColor = backgroundColor,
                    textColor = textColor,
                    onClick = {
                        deviceToRename = device
                        renameText = TextFieldValue(device.customName ?: device.displayName ?: "")
                        showRenameDialog = true
                    }
                )
            }

            if (allDevicesWithState.isEmpty()) {
                item {
                    // Show NoNetworkEmptyState if no LAN connection
                    if (!hasValidLan) {
                        NoNetworkEmptyState(
                            modifier = Modifier.padding(vertical = 32.dp)
                        )
                    } else {
                        // Normal empty state with legend
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 1.dp, horizontal = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Device Status Colors",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Text(
                                text = "Devices are color-coded to show their current status",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            Column(
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                LegendItemLarge(
                                    color = androidx.compose.ui.graphics.Color(0xFFCFE8FF),
                                    label = "New",
                                    description = "Just discovered on this scan"
                                )
                                LegendItemLarge(
                                    color = androidx.compose.ui.graphics.Color(0xFFD7F5DD),
                                    label = "Back Online",
                                    description = "Previously offline, now responding"
                                )
                                LegendItemLarge(
                                    color = androidx.compose.ui.graphics.Color(0xFFF5F5F5),
                                    label = "Still Online",
                                    description = "Was online, continues to respond"
                                )
                                LegendItemLarge(
                                    color = androidx.compose.ui.graphics.Color(0xFFFDE2E2),
                                    label = "Offline",
                                    description = "Recently went offline (within 7 days)"
                                )
                                LegendItemLarge(
                                    color = androidx.compose.ui.graphics.Color(0xFFEEF2F6),
                                    label = "Historical",
                                    description = "Offline for more than 7 days"
                                )
                            }

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "💡",
                                        style = MaterialTheme.typography.headlineSmall,
                                        modifier = Modifier.padding(end = 12.dp)
                                    )
                                    Text(
                                        text = "Tap \"Start Scan\" above to discover devices on your network",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceCard(
    device: StoredDevice,
    deviceState: DeviceState,
    backgroundColor: androidx.compose.ui.graphics.Color,
    textColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        ),
        onClick = onClick
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.ip,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = textColor
                    )
                    Text(
                        text = device.displayName ?: "Unknown Device",
                        style = MaterialTheme.typography.bodyLarge,
                        color = textColor.copy(alpha = 0.8f)
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val badgeLabel = when (deviceState) {
                            DeviceState.NEW -> "New"
                            DeviceState.BACK_ONLINE -> "Back"
                            DeviceState.STILL_ONLINE -> "Still"
                            DeviceState.WENT_OFFLINE -> "Now"
                            DeviceState.HISTORICAL -> "Historical"
                        }

                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = textColor.copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = badgeLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = textColor,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }

                        Text(
                            text = device.status.uppercase(),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = textColor
                        )
                    }

                    if (device.vendor != null) {
                        Text(
                            text = device.vendor,
                            style = MaterialTheme.typography.bodySmall,
                            color = textColor.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LegendItemLarge(
    color: androidx.compose.ui.graphics.Color,
    label: String,
    description: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(color, shape = MaterialTheme.shapes.medium)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}