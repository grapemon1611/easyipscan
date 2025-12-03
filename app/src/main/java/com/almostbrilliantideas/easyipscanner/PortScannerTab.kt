package com.almostbrilliantideas.easyipscanner

import android.content.Context
import android.os.PowerManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

data class PortInfo(
    val port: Int,
    val name: String,
    val isVulnerable: Boolean = false,
    val isCommon: Boolean = false,
    var isSelected: Boolean = false
)

data class PortScanResult(
    val port: Int,
    val name: String,
    val isVulnerable: Boolean,
    val devicesFound: MutableList<String> = mutableListOf()
)

@Composable
fun PortScannerTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var cidr by remember { mutableStateOf(TextFieldValue(detectBestCidr(context) ?: "192.168.1.0/24")) }
    var scanning by remember { mutableStateOf(false) }
    var customPort by remember { mutableStateOf(TextFieldValue("")) }
    var scanResults by remember { mutableStateOf<List<PortScanResult>>(emptyList()) }
    var totalDevicesScanned by remember { mutableStateOf(0) }
    var scanProgress by remember { mutableStateOf(0f) }
    var showResults by remember { mutableStateOf(false) }

    // Wake lock to keep screen on during scan
    val wakeLock = remember {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        powerManager.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "EasyIPScan:PortScan")
    }

    DisposableEffect(Unit) {
        onDispose {
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
        }
    }

    val commonPorts = remember {
        mutableStateListOf(
            PortInfo(22, "SSH", isVulnerable = false, isCommon = true, isSelected = true),
            PortInfo(23, "Telnet", isVulnerable = true, isCommon = false, isSelected = true),
            PortInfo(80, "HTTP", isVulnerable = false, isCommon = true, isSelected = true),
            PortInfo(443, "HTTPS", isVulnerable = false, isCommon = true, isSelected = true),
            PortInfo(445, "SMB", isVulnerable = true, isCommon = false, isSelected = true),
            PortInfo(3389, "RDP", isVulnerable = true, isCommon = false, isSelected = true),
            PortInfo(3306, "MySQL", isVulnerable = true, isCommon = false, isSelected = true),
            PortInfo(5432, "PostgreSQL", isVulnerable = true, isCommon = false, isSelected = true),
            PortInfo(6379, "Redis", isVulnerable = true, isCommon = false, isSelected = true),
            PortInfo(27017, "MongoDB", isVulnerable = true, isCommon = false, isSelected = true),
            PortInfo(21, "FTP", isVulnerable = true, isCommon = false, isSelected = false),
            PortInfo(25, "SMTP", isVulnerable = false, isCommon = false, isSelected = false),
            PortInfo(53, "DNS", isVulnerable = false, isCommon = false, isSelected = false),
            PortInfo(110, "POP3", isVulnerable = false, isCommon = false, isSelected = false),
            PortInfo(143, "IMAP", isVulnerable = false, isCommon = false, isSelected = false),
            PortInfo(5900, "VNC", isVulnerable = true, isCommon = false, isSelected = false),
            PortInfo(8080, "HTTP-Alt", isVulnerable = false, isCommon = false, isSelected = false),
            PortInfo(9200, "Elasticsearch", isVulnerable = true, isCommon = false, isSelected = false)
        )
    }

    if (showResults) {
        PortScanResultsView(
            scanResults = scanResults,
            scanning = scanning,
            scanProgress = scanProgress,
            totalDevicesScanned = totalDevicesScanned,
            onBack = {
                showResults = false
                scanResults = emptyList()
                totalDevicesScanned = 0
                scanProgress = 0f
            }
        )
    } else {
        PortScanSetupView(
            cidr = cidr,
            onCidrChange = { cidr = it },
            customPort = customPort,
            onCustomPortChange = { customPort = it },
            commonPorts = commonPorts,
            scanning = scanning,
            scanResults = scanResults,
            onStartScan = {
                scanning = true
                scanResults = emptyList()
                totalDevicesScanned = 0
                scanProgress = 0f
                showResults = true

                // Acquire wake lock to keep screen on
                if (!wakeLock.isHeld) {
                    wakeLock.acquire(10 * 60 * 1000L) // 10 minute timeout
                }

                scope.launch(Dispatchers.IO) {
                    val selectedPorts = commonPorts.filter { it.isSelected }
                    val results = mutableListOf<PortScanResult>()

                    selectedPorts.forEach { portInfo ->
                        results.add(
                            PortScanResult(
                                port = portInfo.port,
                                name = portInfo.name,
                                isVulnerable = portInfo.isVulnerable
                            )
                        )
                    }

                    withContext(Dispatchers.Main) {
                        scanResults = results
                    }

                    val rangeTriple = parseCidrRange(cidr.text)
                    if (rangeTriple != null) {
                        val (_, first, last) = rangeTriple
                        val range = (first..last).map { intToIp(it) }
                        val totalIPs = range.size
                        var scannedIPs = 0

                        val semaphore = kotlinx.coroutines.sync.Semaphore(10)

                        coroutineScope {
                            val jobs = range.map { ipAddress ->
                                launch {
                                    semaphore.acquire()
                                    try {
                                        selectedPorts.forEach { portInfo ->
                                            if (tcpProbe(ipAddress, portInfo.port, 3000)) {
                                                val resultIndex = results.indexOfFirst { it.port == portInfo.port }
                                                if (resultIndex >= 0) {
                                                    synchronized(results) {
                                                        results[resultIndex].devicesFound.add(ipAddress)
                                                    }
                                                    withContext(Dispatchers.Main) {
                                                        scanResults = results.toList()
                                                    }
                                                }
                                            }
                                        }

                                        scannedIPs++
                                        withContext(Dispatchers.Main) {
                                            totalDevicesScanned = scannedIPs
                                            scanProgress = scannedIPs.toFloat() / totalIPs
                                        }
                                    } finally {
                                        semaphore.release()
                                    }
                                }
                            }
                            jobs.forEach { it.join() }
                        }
                    }

                    withContext(Dispatchers.Main) {
                        scanning = false
                        // Release wake lock when scan completes
                        if (wakeLock.isHeld) {
                            wakeLock.release()
                        }
                    }
                }
            },
            onViewResults = { showResults = true }
        )
    }
}

@Composable
fun PortScanSetupView(
    cidr: TextFieldValue,
    onCidrChange: (TextFieldValue) -> Unit,
    customPort: TextFieldValue,
    onCustomPortChange: (TextFieldValue) -> Unit,
    commonPorts: MutableList<PortInfo>,
    scanning: Boolean,
    scanResults: List<PortScanResult>,
    onStartScan: () -> Unit,
    onViewResults: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Port Scanner",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "Scan network for open ports",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = cidr,
            onValueChange = onCidrChange,
            label = { Text("Network CIDR") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = !scanning
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                onClick = {
                    commonPorts.forEachIndexed { index, port ->
                        commonPorts[index] = port.copy(isSelected = port.isCommon)
                    }
                },
                enabled = !scanning,
                modifier = Modifier.weight(1f)
            ) {
                Text("Common", style = MaterialTheme.typography.labelSmall)
            }

            Button(
                onClick = {
                    commonPorts.forEachIndexed { index, port ->
                        commonPorts[index] = port.copy(isSelected = port.isVulnerable)
                    }
                },
                enabled = !scanning,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFB71C1C)
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text("Vulnerable", style = MaterialTheme.typography.labelSmall)
            }

            Button(
                onClick = {
                    commonPorts.forEachIndexed { index, port ->
                        commonPorts[index] = port.copy(isSelected = true)
                    }
                },
                enabled = !scanning,
                modifier = Modifier.weight(1f)
            ) {
                Text("All", style = MaterialTheme.typography.labelSmall)
            }

            Button(
                onClick = {
                    commonPorts.forEachIndexed { index, port ->
                        commonPorts[index] = port.copy(isSelected = false)
                    }
                },
                enabled = !scanning,
                modifier = Modifier.weight(1f)
            ) {
                Text("Clear", style = MaterialTheme.typography.labelSmall)
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .padding(12.dp)
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                commonPorts.chunked(2).forEach { rowPorts ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowPorts.forEach { portInfo ->
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = portInfo.isSelected,
                                    onCheckedChange = { checked ->
                                        val index = commonPorts.indexOf(portInfo)
                                        commonPorts[index] = portInfo.copy(isSelected = checked)
                                    },
                                    enabled = !scanning
                                )

                                Column {
                                    Text(
                                        text = portInfo.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (portInfo.isVulnerable) Color(0xFFB71C1C)
                                        else if (portInfo.isCommon) Color(0xFF1B5E20)
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "(${portInfo.port})",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        if (rowPorts.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = customPort,
                onValueChange = onCustomPortChange,
                label = { Text("Custom Port") },
                placeholder = { Text("e.g., 8080") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                enabled = !scanning
            )

            IconButton(
                onClick = {
                    val port = customPort.text.toIntOrNull()
                    if (port != null && port in 1..65535) {
                        if (commonPorts.none { it.port == port }) {
                            commonPorts.add(
                                PortInfo(port, "Custom-$port", isVulnerable = false, isCommon = false, isSelected = true)
                            )
                        }
                        onCustomPortChange(TextFieldValue(""))
                    }
                },
                enabled = !scanning
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add custom port")
            }
        }

        Button(
            onClick = onStartScan,
            enabled = !scanning && commonPorts.any { it.isSelected },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF000099)
            )
        ) {
            Text("Start Network Scan")
        }

        if (scanResults.isNotEmpty()) {
            Button(
                onClick = onViewResults,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1B5E20)
                )
            ) {
                Text("View Results (${scanResults.count { it.devicesFound.isNotEmpty() }} ports found)")
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Select ports and tap 'Start Network Scan'",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun PortScanResultsView(
    scanResults: List<PortScanResult>,
    scanning: Boolean,
    scanProgress: Float,
    totalDevicesScanned: Int,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Port Scan Results",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = if (scanning) "Scanning $totalDevicesScanned devices..."
                    else "${scanResults.count { it.devicesFound.isNotEmpty() }} ports with devices found",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(
                onClick = onBack,
                enabled = !scanning
            ) {
                Text("New Scan")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (scanning) {
            LinearProgressIndicator(
                progress = scanProgress,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(scanResults.sortedBy { it.port }) { result ->
                PortResultCard(result)
            }
        }
    }
}

@Composable
fun PortResultCard(result: PortScanResult) {
    val backgroundColor = when {
        result.devicesFound.isEmpty() -> Color(0xFFF5F5F5)
        result.isVulnerable -> Color(0xFFFDE2E2)
        else -> Color(0xFFD7F5DD)
    }

    val textColor = when {
        result.devicesFound.isEmpty() -> Color(0xFF757575)
        result.isVulnerable -> Color(0xFFB71C1C)
        else -> Color(0xFF1B5E20)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${result.name} (${result.port})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = textColor
                        )
                        if (result.isVulnerable && result.devicesFound.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "⚠️",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }

                    Text(
                        text = if (result.devicesFound.isEmpty()) {
                            if (result.isVulnerable) "✓ No devices found (good!)" else "No devices found"
                        } else {
                            "${result.devicesFound.size} device(s) found"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor.copy(alpha = 0.8f)
                    )
                }
            }

            if (result.devicesFound.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    result.devicesFound.sorted().forEach { ip ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text(
                                text = "• ",
                                style = MaterialTheme.typography.bodyMedium,
                                color = textColor
                            )
                            Text(
                                text = ip,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = textColor
                            )
                        }
                    }
                }
            }
        }
    }
}