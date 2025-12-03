package com.almostbrilliantideas.easyipscanner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class WiFiInfo(
    val ssid: String,
    val bssid: String,
    val ipAddress: String,
    val gateway: String,
    val frequency: Int,
    val wifiStandard: String,
    val linkSpeed: Int,
    val rssi: Int,
    val signalQuality: Int,
    val channel: Int,
    val channelWidth: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WiFiTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var wifiInfo by remember { mutableStateOf<WiFiInfo?>(null) }
    var loading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var hasPermission by remember { mutableStateOf(false) }

    fun loadWiFiInfo() {
        loading = true
        errorMessage = null
        scope.launch(Dispatchers.IO) {
            try {
                val info = getWiFiInfo(context)
                withContext(Dispatchers.Main) {
                    wifiInfo = info
                    loading = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    errorMessage = e.message ?: "Failed to get WiFi info"
                    loading = false
                }
            }
        }
    }

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasPermission = isGranted
        if (isGranted) {
            loadWiFiInfo()
        } else {
            errorMessage = "Location permission required to view WiFi details"
        }
    }

    // Check permission on launch
    LaunchedEffect(Unit) {
        val permission = Manifest.permission.ACCESS_FINE_LOCATION
        hasPermission = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            loadWiFiInfo()
        } else {
            permissionLauncher.launch(permission)
        }
    }

    LaunchedEffect(Unit) {
        loadWiFiInfo()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WiFi Diagnostics") },
                actions = {
                    IconButton(onClick = { loadWiFiInfo() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when {
                loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                errorMessage != null -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFFDE2E2)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Error",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFB71C1C)
                            )
                            Text(
                                text = errorMessage ?: "Unknown error",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFB71C1C)
                            )
                        }
                    }
                }

                wifiInfo != null -> {
                    // Connection Info Card
                    InfoCard(
                        title = "Connection",
                        items = listOf(
                            "Network (SSID)" to wifiInfo!!.ssid,
                            "IP Address" to wifiInfo!!.ipAddress,
                            "Gateway" to wifiInfo!!.gateway
                        )
                    )

                    // Signal Strength Card
                    SignalStrengthCard(wifiInfo!!.rssi, wifiInfo!!.signalQuality)

                    // Performance Card
                    InfoCard(
                        title = "Performance",
                        items = listOf(
                            "Frequency" to "${wifiInfo!!.frequency} MHz (${if (wifiInfo!!.frequency > 5000) "5GHz" else "2.4GHz"})",
                            "WiFi Standard" to wifiInfo!!.wifiStandard,
                            "Link Speed" to "${wifiInfo!!.linkSpeed} Mbps",
                            "Channel" to "${wifiInfo!!.channel}"
                        )
                    )

                    // Recommendations Card
                    RecommendationsCard(wifiInfo!!)
                }
            }
        }
    }
}

@Composable
fun InfoCard(title: String, items: List<Pair<String, String>>) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            items.forEach { (label, value) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
fun SignalStrengthCard(rssi: Int, quality: Int) {
    val signalColor = when {
        quality >= 80 -> Color(0xFF1B5E20) // Excellent - Green
        quality >= 60 -> Color(0xFF558B2F) // Good - Light Green
        quality >= 40 -> Color(0xFFF57F17) // Fair - Yellow
        quality >= 20 -> Color(0xFFE65100) // Poor - Orange
        else -> Color(0xFFB71C1C) // Very Poor - Red
    }

    val signalText = when {
        quality >= 80 -> "Excellent"
        quality >= 60 -> "Good"
        quality >= 40 -> "Fair"
        quality >= 20 -> "Poor"
        else -> "Very Poor"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = signalColor.copy(alpha = 0.1f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Signal Strength",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = signalText,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = signalColor
                    )
                    Text(
                        text = "$rssi dBm ($quality%)",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            LinearProgressIndicator(
                progress = quality / 100f,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = signalColor,
                trackColor = signalColor.copy(alpha = 0.2f)
            )
        }
    }
}

@Composable
fun RecommendationsCard(info: WiFiInfo) {
    val recommendations = mutableListOf<String>()

    // Signal strength recommendation
    if (info.signalQuality < 60) {
        recommendations.add("⚠️ Weak signal detected. Move closer to the router or consider a WiFi extender.")
    }

    // Frequency recommendation
    if (info.frequency < 5000 && info.signalQuality >= 60) {
        recommendations.add("💡 You're on 2.4GHz. Switch to 5GHz for faster speeds if available.")
    }

    // Channel congestion (basic heuristic)
    if (info.channel in listOf(1, 6, 11) && info.signalQuality < 70) {
        recommendations.add("📡 Channel ${info.channel} is commonly congested. Try a different channel in your router settings.")
    }

    // Link speed vs frequency mismatch
    if (info.frequency > 5000 && info.linkSpeed < 100) {
        recommendations.add("⚡ Link speed is low for 5GHz. Check for interference or router configuration.")
    }

    if (recommendations.isEmpty()) {
        recommendations.add("✅ Your WiFi connection looks good! No issues detected.")
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (recommendations.size == 1 && recommendations[0].startsWith("✅"))
                Color(0xFFD7F5DD) else Color(0xFFFFF9C4)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Recommendations",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            recommendations.forEach { recommendation ->
                Text(
                    text = recommendation,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}

fun getWiFiInfo(context: Context): WiFiInfo {
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val wifiInfo = wifiManager.connectionInfo
    val dhcpInfo = wifiManager.dhcpInfo

    // Get SSID (remove quotes)
    val ssid = wifiInfo.ssid.replace("\"", "")

    // Get BSSID
    val bssid = wifiInfo.bssid ?: "Unknown"

    // Get IP address
    val ipInt = dhcpInfo.ipAddress
    val ipAddress = if (ipInt != 0) {
        String.format(
            "%d.%d.%d.%d",
            ipInt and 0xff,
            ipInt shr 8 and 0xff,
            ipInt shr 16 and 0xff,
            ipInt shr 24 and 0xff
        )
    } else {
        "Unknown"
    }

    // Get gateway
    val gatewayInt = dhcpInfo.gateway
    val gateway = if (gatewayInt != 0) {
        String.format(
            "%d.%d.%d.%d",
            gatewayInt and 0xff,
            gatewayInt shr 8 and 0xff,
            gatewayInt shr 16 and 0xff,
            gatewayInt shr 24 and 0xff
        )
    } else {
        "Unknown"
    }

    // Get frequency
    val frequency = wifiInfo.frequency

    // Get WiFi standard (Android 11+)
    val wifiStandard = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        when (wifiInfo.wifiStandard) {
            4 -> "802.11n (WiFi 4)"
            5 -> "802.11ac (WiFi 5)"
            6 -> "802.11ax (WiFi 6)"
            7 -> "802.11be (WiFi 7)"
            else -> "Unknown"
        }
    } else {
        // Infer from frequency for older Android
        if (frequency > 5000) "802.11ac (WiFi 5)" else "802.11n (WiFi 4)"
    }

    // Get link speed
    val linkSpeed = wifiInfo.linkSpeed

    // Get RSSI (signal strength)
    val rssi = wifiInfo.rssi

    // Calculate signal quality percentage
    val signalQuality = calculateSignalQuality(rssi)

    // Get channel from frequency
    val channel = frequencyToChannel(frequency)

    return WiFiInfo(
        ssid = ssid,
        bssid = bssid,
        ipAddress = ipAddress,
        gateway = gateway,
        frequency = frequency,
        wifiStandard = wifiStandard,
        linkSpeed = linkSpeed,
        rssi = rssi,
        signalQuality = signalQuality,
        channel = channel,
        channelWidth = "N/A"
    )
}

fun calculateSignalQuality(rssi: Int): Int {
    // RSSI typically ranges from -100 (worst) to -30 (best)
    // Convert to 0-100 percentage
    return when {
        rssi >= -30 -> 100
        rssi <= -100 -> 0
        else -> {
            val quality = 2 * (rssi + 100)
            quality.coerceIn(0, 100)
        }
    }
}

fun frequencyToChannel(frequency: Int): Int {
    return when {
        frequency in 2412..2484 -> {
            // 2.4 GHz band
            (frequency - 2412) / 5 + 1
        }
        frequency in 5170..5825 -> {
            // 5 GHz band
            (frequency - 5000) / 5
        }
        else -> 0
    }
}