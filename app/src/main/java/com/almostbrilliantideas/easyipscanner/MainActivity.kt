package com.almostbrilliantideas.easyipscanner

import android.content.Context
import android.net.DhcpInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.*
import java.nio.ByteBuffer
import java.nio.charset.Charset
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener
import kotlin.math.max

data class ScanResult(
    val ip: String,
    val status: String,
    val details: String = "",
    val latencyMs: Long? = null,
    val hostname: String? = null,
    val mac: String? = null,
    val vendor: String? = null
)

// Container for all discovered names from different sources
data class DeviceNames(
    val rokuHttp: String? = null,
    val ssdp: String? = null,
    val mdns: String? = null,
    val netbios: String? = null,
    val dns: String? = null
) {
    // Smart selection: prefer user-friendly names over technical names
    fun getBestName(): String? {
        // Priority order:
        // 1. SSDP friendly name (e.g., "55inTCLRokuTV")
        // 2. Roku HTTP user-device-name
        // 3. mDNS name (but filter out serial numbers)
        // 4. NetBIOS
        // 5. DNS reverse lookup

        ssdp?.let { if (it.isUserFriendly()) return it }
        rokuHttp?.let { if (it.isUserFriendly()) return it }
        mdns?.let { if (it.isUserFriendly()) return it }
        netbios?.let { if (it.isUserFriendly()) return it }
        dns?.let { if (it.isUserFriendly()) return it }

        // If nothing is user-friendly, return the first non-null
        return ssdp ?: rokuHttp ?: mdns ?: netbios ?: dns
    }

    private fun String.isUserFriendly(): Boolean {
        // Filter out serial numbers and technical names
        val cleaned = this.removeSuffix(".local").removeSuffix(".lan")

        // Serial number patterns to reject
        val isSerialNumber = cleaned.matches(Regex("X[0-9A-Z]{8,}")) || // X00000LDU0R2
                cleaned.matches(Regex("[0-9A-F]{12}")) ||  // MAC-like
                cleaned.matches(Regex(".*-[0-9a-f]{6}$"))  // ends with hex

        // Must be at least 3 chars and not a serial number
        return cleaned.length >= 3 && !isSerialNumber
    }

    fun toDebugString(): String {
        return buildString {
            append("SSDP:$ssdp ")
            append("Roku:$rokuHttp ")
            append("mDNS:$mdns ")
            append("NetBIOS:$netbios ")
            append("DNS:$dns")
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppRoot() }
    }
}

@Composable
fun AppRoot() {
    var tabIndex by remember { mutableStateOf(0) }
    val tabs = listOf("Ping", "Scan Network")

    Column {
        Text(
            text = "EasyIP Scanner",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(16.dp)
        )
        TabRow(selectedTabIndex = tabIndex) {
            tabs.forEachIndexed { i, t ->
                Tab(
                    selected = tabIndex == i,
                    onClick = { tabIndex = i },
                    text = { Text(t) }
                )
            }
        }
        when (tabIndex) {
            0 -> PingTab()
            else -> ScanTab()
        }
    }
}

@Composable
fun PingTab() {
    val ctx = LocalContext.current
    var ip by remember { mutableStateOf(TextFieldValue("192.168.1.1")) }
    var output by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            OuiCache.ensureLoaded(ctx)
        }
    }

    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Step 1: Ping one IP", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = ip,
            onValueChange = { ip = it },
            label = { Text("IP address") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            enabled = !working,
            onClick = {
                working = true
                output = "Pinging ${ip.text}…"
                scope.launch(Dispatchers.IO) {
                    val res = pingHost(ip.text)
                    val names = discoverAllNames(ip.text)
                    val bestName = names.getBestName()
                    val mac = readArpCache()[ip.text]
                    val vendor = vendorFromMac(mac)
                    withContext(Dispatchers.Main) {
                        output = buildString {
                            append(res)
                            append("\n\n=== Device Info ===")
                            append("\nBest Name: ")
                            append(bestName ?: "(unable to resolve)")
                            append("\n\nAll Discovery Results:")
                            append("\n${names.toDebugString()}")
                            if (mac != null) {
                                append("\n\nMAC: ").append(mac)
                            } else {
                                append("\n\nMAC: (unavailable - Android 10+ restriction)")
                            }
                            if (vendor != null) {
                                append("\nVendor: ").append(vendor)
                            }
                        }
                        working = false
                    }
                }
            }
        ) {
            Text(if (working) "Working…" else "Ping")
        }
        Text(output)
    }
}

@Composable
fun ScanTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val mdns = remember { MdnsActiveCache(context) }
    val ssdp = remember { SsdpDiscovery(context) }
    var discoveryWaitTime by remember { mutableStateOf(15) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            OuiCache.ensureLoaded(context)

            println("Starting mDNS...")
            mdns.start()

            println("Starting SSDP...")
            ssdp.start()

            for (i in 15 downTo 0) {
                withContext(Dispatchers.Main) {
                    discoveryWaitTime = i
                }
                delay(1000)
            }
            println("Discovery services running. mDNS: ${mdns.names.size}, SSDP: ${ssdp.names.size}")
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            mdns.stop()
            ssdp.stop()
        }
    }

    var cidr by remember { mutableStateOf(TextFieldValue(detectBestCidr(context) ?: "192.168.1.0/24")) }
    var concurrency by remember { mutableStateOf(TextFieldValue("80")) }
    var timeoutMs by remember { mutableStateOf(TextFieldValue("500")) }
    var scanning by remember { mutableStateOf(false) }
    val results = remember { mutableStateListOf<ScanResult>() }
    val jobRef = remember { mutableStateOf<Job?>(null) }

    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Step 2: Scan Network", style = MaterialTheme.typography.titleLarge)

        if (discoveryWaitTime > 0) {
            Text(
                text = "⏳ Discovering devices... (${discoveryWaitTime}s remaining)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary
            )
        }

        Text(
            text = "mDNS: ${mdns.names.size} | SSDP: ${ssdp.names.size} devices",
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
            OutlinedTextField(
                value = concurrency,
                onValueChange = { concurrency = it },
                label = { Text("Concurrency") },
                singleLine = true,
                modifier = Modifier.width(160.dp)
            )
            OutlinedTextField(
                value = timeoutMs,
                onValueChange = { timeoutMs = it },
                label = { Text("Timeout ms") },
                singleLine = true,
                modifier = Modifier.width(160.dp)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !scanning,
                onClick = {
                    results.clear()
                    scanning = true

                    val conc = concurrency.text.toIntOrNull()?.coerceIn(4, 256) ?: 80
                    val toMs = timeoutMs.text.toIntOrNull()?.coerceIn(100, 5000) ?: 500

                    val typed = cidr.text.trim()
                    val detected = detectBestCidr(context)
                    val effectiveCidr = normalizeToNetworkCidr(
                        candidate = typed.ifEmpty { detected ?: "" },
                        fallback = detected ?: "192.168.1.0/24"
                    )
                    cidr = TextFieldValue(effectiveCidr)

                    jobRef.value = scope.launch(Dispatchers.IO) {
                        val initialArp = readArpCache()

                        withContext(Dispatchers.Main) {
                            if (initialArp.isEmpty()) {
                                results.add(0, ScanResult(
                                    ip = "NOTICE",
                                    status = "Android 10+ blocks ARP",
                                    details = "MAC addresses unavailable",
                                    latencyMs = null,
                                    hostname = null
                                ))
                            }
                        }

                        scanCidr(effectiveCidr, conc, toMs, mdns, ssdp) { r ->
                            runBlocking(Dispatchers.Main) {
                                val mac = initialArp[r.ip] ?: readArpCache()[r.ip]
                                val vendor = vendorFromMac(mac)

                                results.add(
                                    r.copy(
                                        mac = mac,
                                        vendor = vendor
                                    )
                                )
                            }
                        }

                        runBlocking(Dispatchers.Main) {
                            scanning = false
                        }
                    }
                }
            ) {
                Text("Start Scan")
            }

            Button(
                enabled = scanning,
                onClick = {
                    jobRef.value?.cancel()
                    scanning = false
                }
            ) {
                Text("Stop")
            }
        }

        Text("Live results (${results.size})", style = MaterialTheme.typography.titleMedium)

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            items(results, key = { it.ip }) { r ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = r.ip,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                if (r.hostname != null) {
                                    Text(
                                        text = r.hostname,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = r.status,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (r.latencyMs != null) {
                                    Text(
                                        text = "${r.latencyMs}ms",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        if (r.mac != null) {
                            Text(
                                text = "MAC: ${r.mac}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        if (r.vendor != null) {
                            Text(
                                text = "Vendor: ${r.vendor}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }

                        if (r.details.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = r.details.take(200),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

// Discover names from ALL sources and return them all
private fun discoverAllNames(ip: String, timeoutMs: Int = 2000): DeviceNames {
    val names = mutableMapOf<String, String?>()

    // Run all discovery methods in parallel
    runBlocking {
        val jobs = listOf(
            async(Dispatchers.IO) {
                try {
                    // Roku needs longer timeout - fixed 3 seconds
                    names["roku"] = getRokuName(ip, 3000)
                    println("$ip Roku HTTP: ${names["roku"]}")
                } catch (e: Exception) {
                    println("$ip Roku HTTP failed: ${e.message}")
                }
            },
            async(Dispatchers.IO) {
                try {
                    names["netbios"] = netbiosLookup(ip, timeoutMs)
                    println("$ip NetBIOS: ${names["netbios"]}")
                } catch (e: Exception) {
                    println("$ip NetBIOS failed: ${e.message}")
                }
            },
            async(Dispatchers.IO) {
                try {
                    names["dns"] = reverseDns(ip)
                    println("$ip DNS: ${names["dns"]}")
                } catch (e: Exception) {
                    println("$ip DNS failed: ${e.message}")
                }
            }
        )
        jobs.joinAll()
    }

    return DeviceNames(
        rokuHttp = names["roku"],
        netbios = names["netbios"],
        dns = names["dns"]
    )
}

private fun detectBestCidr(ctx: Context): String? {
    val detected = getWifiCidr(ctx) ?: getInterfaceCidr()
    println("CIDR Detection: $detected")
    return detected
}

private fun getWifiCidr(ctx: Context): String? {
    return try {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val dhcp: DhcpInfo = wm.dhcpInfo ?: return null
        val ipIntLE = dhcp.ipAddress
        val maskLE = dhcp.netmask
        if (ipIntLE == 0 || maskLE == 0) return null
        val ip = Integer.reverseBytes(ipIntLE)
        val mask = Integer.reverseBytes(maskLE)
        val prefix = Integer.bitCount(mask)
        val network = ip and mask
        "${intToIp(network)}/$prefix"
    } catch (_: Exception) {
        null
    }
}

private fun getInterfaceCidr(): String? {
    return try {
        val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (nif in ifaces) {
            if (!nif.isUp || nif.isLoopback) continue
            for (ia in nif.interfaceAddresses) {
                val addr = ia.address
                val prefix = ia.networkPrefixLength.toInt()
                if (addr is Inet4Address && prefix in 1..32) {
                    val ip = ipToInt(addr.hostAddress) ?: continue
                    val mask = if (prefix == 32) -1 else (-1 shl (32 - prefix))
                    val network = ip and mask
                    return "${intToIp(network)}/$prefix"
                }
            }
        }
        null
    } catch (_: Exception) {
        null
    }
}

private fun normalizeToNetworkCidr(candidate: String, fallback: String): String {
    val parts = candidate.split("/")
    if (parts.size == 2) {
        val ip = ipToInt(parts[0])
        val prefix = parts[1].toIntOrNull()
        if (ip != null && prefix != null && prefix in 1..32) {
            val mask = if (prefix == 32) -1 else (-1 shl (32 - prefix))
            val network = ip and mask
            return "${intToIp(network)}/$prefix"
        }
    }
    return fallback
}

private fun getRokuName(ip: String, timeoutMs: Int = 1500): String? {
    return try {
        val url = URL("http://$ip:8060/query/device-info")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs

        val code = conn.responseCode
        if (code == 200) {
            val xml = conn.inputStream.bufferedReader().use { it.readText() }

            // Try user-device-name first (custom name)
            val userNameMatch = Regex("<user-device-name>(.+?)</user-device-name>", RegexOption.IGNORE_CASE).find(xml)
            if (userNameMatch != null) {
                return userNameMatch.groupValues[1].trim()
            }

            // Fall back to friendly-device-name
            val friendlyMatch = Regex("<friendly-device-name>(.+?)</friendly-device-name>", RegexOption.IGNORE_CASE).find(xml)
            if (friendlyMatch != null) {
                return friendlyMatch.groupValues[1].trim()
            }

            // Last resort: model name
            val modelMatch = Regex("<model-name>(.+?)</model-name>", RegexOption.IGNORE_CASE).find(xml)
            return modelMatch?.groupValues?.get(1)?.trim()
        }
        conn.disconnect()
        null
    } catch (e: Exception) {
        null
    }
}

private fun reverseDns(ip: String): String? {
    return try {
        val parts = ip.split('.').map { it.toInt().toByte() }.toByteArray()
        val addr = InetAddress.getByAddress(parts)
        val name = addr.canonicalHostName
        if (name == addr.hostAddress) null else name
    } catch (_: Exception) {
        null
    }
}

private fun netbiosLookup(ip: String, timeoutMs: Int): String? {
    return try {
        val socket = DatagramSocket()
        socket.soTimeout = timeoutMs

        val tid = (System.nanoTime().toInt() and 0xFFFF)
        val query = buildNetbiosQuery(tid)
        val target = InetAddress.getByName(ip)

        socket.send(DatagramPacket(query, query.size, target, 137))

        val buf = ByteArray(1024)
        val resp = DatagramPacket(buf, buf.size)
        socket.receive(resp)

        val result = parseNetbiosName(buf, resp.length)
        socket.close()

        result
    } catch (e: Exception) {
        null
    }
}

private fun buildNetbiosQuery(tid: Int): ByteArray {
    val bb = ByteBuffer.allocate(50)
    bb.putShort(tid.toShort())
    bb.putShort(0x0010.toShort())
    bb.putShort(0x0001.toShort())
    bb.putShort(0x0000.toShort())
    bb.putShort(0x0000.toShort())
    bb.putShort(0x0000.toShort())
    bb.put(encodeNetbiosName("*"))
    bb.putShort(0x0021.toShort())
    bb.putShort(0x0001.toShort())
    return bb.array()
}

private fun encodeNetbiosName(n: String): ByteArray {
    val out = ByteArray(34)
    out[0] = 0x20
    val upper = n.uppercase().padEnd(15, ' ')
    val bytes = upper.toByteArray(Charset.forName("US-ASCII"))
    for (i in 0 until 16) {
        val c = if (i < bytes.size) bytes[i].toInt() and 0xFF else 0x20
        val high = ((c shr 4) and 0x0F) + 'A'.code
        val low = (c and 0x0F) + 'A'.code
        out[1 + i * 2] = high.toByte()
        out[1 + i * 2 + 1] = low.toByte()
    }
    out[33] = 0x00
    return out
}

private fun parseNetbiosName(buf: ByteArray, len: Int): String? {
    if (len < 57) return null
    var p = 12
    while (p < len && buf[p].toInt() != 0) p++
    p++
    if (p + 4 > len) return null
    p += 4
    if (p + 10 > len) return null
    p += 2
    val type = ((buf[p].toInt() and 0xFF) shl 8) or (buf[p + 1].toInt() and 0xFF)
    p += 2
    p += 2
    p += 4
    val rdlen = ((buf[p].toInt() and 0xFF) shl 8) or (buf[p + 1].toInt() and 0xFF)
    p += 2
    if (type != 0x0021 || p + rdlen > len) return null
    if (p + 1 > len) return null
    val numNames = buf[p].toInt() and 0xFF
    p += 1
    for (i in 0 until numNames) {
        if (p + 18 > len) break
        val nameBytes = buf.copyOfRange(p, p + 15)
        val raw = String(nameBytes, Charset.forName("US-ASCII")).trim()
        p += 18
        if (raw.isNotBlank()) return raw
    }
    return null
}

private fun pingHost(host: String): String {
    systemPing(host, 1, 1000)?.let { return it }
    if (tcpProbe(host, 80, 300)) return "TCP probe to $host:80 succeeded (host likely up)."
    if (tcpProbe(host, 443, 300)) return "TCP probe to $host:443 succeeded (host likely up)."
    return "No response from $host (ICMP blocked or host down)."
}

private fun systemPing(host: String, count: Int, timeoutMs: Int): String? {
    return try {
        val process = ProcessBuilder(
            "/system/bin/ping", "-c", count.toString(),
            "-W", max(1, timeoutMs / 1000).toString(), host
        ).redirectErrorStream(true).start()
        val out = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
        val code = process.waitFor()
        if (code == 0) "ICMP ping to $host succeeded.\n$out" else null
    } catch (_: Exception) {
        null
    }
}

private fun tcpProbe(host: String, port: Int, timeoutMs: Int): Boolean {
    return try {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), timeoutMs)
            true
        }
    } catch (_: Exception) {
        false
    }
}

private fun scanCidr(
    cidr: String,
    concurrency: Int,
    timeoutMs: Int,
    mdns: MdnsActiveCache,
    ssdp: SsdpDiscovery,
    onResult: (ScanResult) -> Unit
) {
    val parsed = parseCidrRange(cidr)
    if (parsed == null) {
        onResult(ScanResult(cidr, "Invalid CIDR", "Use format like 10.0.0.0/24"))
        return
    }
    val (_, first, last) = parsed

    val sem = Semaphore(concurrency)
    val parent = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val jobs = (first..last).map { ipInt ->
        parent.launch {
            sem.withPermit {
                val ip = intToIp(ipInt)
                var emitted = false
                val start = System.currentTimeMillis()
                try {
                    val out = systemPing(ip, 1, timeoutMs)
                    if (out != null) {
                        // Device is alive - discover ALL names
                        val discoveredNames = discoverAllNames(ip, timeoutMs)

                        // Add cached names from background services
                        val allNames = discoveredNames.copy(
                            ssdp = synchronized(ssdp.names) { ssdp.names[ip] } ?: discoveredNames.ssdp,
                            mdns = synchronized(mdns.names) { mdns.names[ip] } ?: discoveredNames.mdns
                        )

                        val bestName = allNames.getBestName()

                        println("$ip names: ${allNames.toDebugString()} -> BEST: $bestName")

                        onResult(ScanResult(
                            ip = ip,
                            status = "ICMP",
                            details = allNames.toDebugString(),
                            latencyMs = System.currentTimeMillis() - start,
                            hostname = bestName
                        ))
                        emitted = true
                    } else {
                        var hit = false
                        for (p in listOf(80, 443, 8060, 22, 23)) {
                            if (tcpProbe(ip, p, timeoutMs)) {
                                val discoveredNames = discoverAllNames(ip, timeoutMs)
                                val allNames = discoveredNames.copy(
                                    ssdp = synchronized(ssdp.names) { ssdp.names[ip] } ?: discoveredNames.ssdp,
                                    mdns = synchronized(mdns.names) { mdns.names[ip] } ?: discoveredNames.mdns
                                )
                                val bestName = allNames.getBestName()

                                onResult(ScanResult(
                                    ip = ip,
                                    status = "TCP:$p",
                                    details = allNames.toDebugString(),
                                    latencyMs = System.currentTimeMillis() - start,
                                    hostname = bestName
                                ))
                                emitted = true
                                hit = true
                                break
                            }
                        }
                        if (!hit) {
                            onResult(ScanResult(
                                ip = ip,
                                status = "No response",
                                details = "",
                                latencyMs = System.currentTimeMillis() - start,
                                hostname = null
                            ))
                            emitted = true
                        }
                    }
                } catch (_: CancellationException) {
                } catch (e: Exception) {
                    onResult(ScanResult(ip, "Error", e.message ?: "err"))
                    emitted = true
                }
                if (!emitted) onResult(ScanResult(ip, "Unknown", "No emission path taken"))
            }
        }
    }

    runBlocking { jobs.joinAll() }
    parent.cancel()
}

private fun parseCidrRange(cidr: String): Triple<Int, Int, Int>? {
    val parts = cidr.split("/")
    if (parts.size != 2) return null
    val base = ipToInt(parts[0]) ?: return null
    val prefix = parts[1].toIntOrNull() ?: return null
    if (prefix !in 1..32) return null
    val mask = if (prefix == 32) -1 else (-1 shl (32 - prefix))
    val network = base and mask
    val broadcast = network or mask.inv()
    val first = if (prefix == 32) network else network + 1
    val last = if (prefix == 32) network else broadcast - 1
    return Triple(network, first, last)
}

private fun ipToInt(ip: String): Int? {
    val parts = ip.split(".").mapNotNull { it.toIntOrNull() }
    if (parts.size != 4) return null
    for (p in parts) if (p !in 0..255) return null
    return (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
}

private fun intToIp(v: Int): String =
    "${(v ushr 24) and 0xFF}.${(v ushr 16) and 0xFF}.${(v ushr 8) and 0xFF}.${v and 0xFF}"

private fun readArpCache(): Map<String, String> {
    val out = mutableMapOf<String, String>()
    try {
        val f = java.io.File("/proc/net/arp")
        if (!f.exists()) return out
        f.useLines { lines ->
            lines.drop(1).forEach { line ->
                val cols = line.trim().split(Regex("\\s+"))
                if (cols.size >= 4) {
                    val ip = cols[0]
                    val mac = cols[3]
                    if (mac.matches(Regex("..:..:..:..:..:.."))) out[ip] = mac.uppercase()
                }
            }
        }
    } catch (_: Exception) {
    }
    return out
}

private fun vendorFromMac(mac: String?): String? {
    if (mac == null) return null
    val prefix = mac.split(":").take(3).joinToString(":")
    return OuiCache.lookup(prefix)
}

private object OuiCache {
    private var loaded = false
    private val map = HashMap<String, String>(16384)

    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching {
                ctx.assets.open("oui.csv").bufferedReader().use { br ->
                    br.lineSequence().forEach { raw ->
                        val line = raw.trim()
                        if (line.isEmpty() || line.startsWith("#")) return@forEach
                        val parts = line.split('\t', ',')
                        if (parts.size >= 2) {
                            val pfx = normalizePrefix(parts[0]) ?: return@forEach
                            val vendor = parts[1].trim()
                            if (vendor.isNotEmpty()) map.putIfAbsent(pfx, vendor)
                        }
                    }
                }
            }
            loaded = true
        }
    }

    private fun normalizePrefix(s: String): String? {
        val t = s.trim().uppercase()
        val hex = t.replace(":", "").replace("-", "")
        if (hex.length < 6) return null
        val a = hex.substring(0, 2)
        val b = hex.substring(2, 4)
        val c = hex.substring(4, 6)
        if (!a.matches(Regex("[0-9A-F]{2}")) ||
            !b.matches(Regex("[0-9A-F]{2}")) ||
            !c.matches(Regex("[0-9A-F]{2}"))
        ) return null
        return "$a:$b:$c"
    }

    fun lookup(prefix: String): String? = map[prefix]
}

class SsdpDiscovery(context: Context) {
    val names = mutableMapOf<String, String>()
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null
    private var listenerThread: Thread? = null
    @Volatile private var running = false

    fun start() {
        println("SSDP: Starting discovery...")
        try {
            multicastLock = wifiManager.createMulticastLock("EasyIPScanner_SSDP")
            multicastLock?.acquire()
            println("SSDP: Multicast lock acquired")

            running = true

            listenerThread = Thread {
                println("SSDP: Thread started")
                try {
                    val socket = DatagramSocket()
                    socket.soTimeout = 3000

                    // FIXED: Use roku:ecp instead of ssdp:all for Roku devices
                    val ssdpRequest = """
                        M-SEARCH * HTTP/1.1
                        HOST: 239.255.255.250:1900
                        MAN: "ssdp:discover"
                        MX: 3
                        ST: roku:ecp
                        
                        
                    """.trimIndent().replace("\n", "\r\n")

                    val searchPacket = DatagramPacket(
                        ssdpRequest.toByteArray(),
                        ssdpRequest.length,
                        InetAddress.getByName("239.255.255.250"),
                        1900
                    )

                    println("SSDP: Sending M-SEARCH with ST: roku:ecp")
                    socket.send(searchPacket)

                    val endTime = System.currentTimeMillis() + 10000
                    val buffer = ByteArray(2048)
                    var responseCount = 0

                    while (running && System.currentTimeMillis() < endTime) {
                        try {
                            val packet = DatagramPacket(buffer, buffer.size)
                            socket.receive(packet)
                            responseCount++

                            val response = String(buffer, 0, packet.length)
                            val sourceIp = packet.address.hostAddress

                            println("SSDP: Response #$responseCount from $sourceIp")

                            if (sourceIp != null) {
                                parseSsdpResponse(response, sourceIp)
                            }
                        } catch (e: SocketTimeoutException) {
                            // Normal
                        } catch (e: Exception) {
                            if (running) {
                                println("SSDP: Error: ${e.message}")
                            }
                        }
                    }

                    socket.close()
                    println("SSDP: Discovery complete ($responseCount responses)")
                } catch (e: Exception) {
                    println("SSDP: Thread error: ${e.javaClass.simpleName}: ${e.message}")
                    e.printStackTrace()
                }
            }
            listenerThread?.start()
            println("SSDP: Thread.start() called")

        } catch (e: Exception) {
            println("SSDP: Failed to start: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
        }
    }

    fun stop() {
        running = false
        listenerThread?.interrupt()
        multicastLock?.release()
    }

    private fun parseSsdpResponse(response: String, sourceIp: String) {
        try {
            val locationRegex = Regex("LOCATION: *(.+)", RegexOption.IGNORE_CASE)
            val locationMatch = locationRegex.find(response)

            if (locationMatch != null) {
                val locationUrl = locationMatch.groupValues[1].trim()
                println("SSDP: Found LOCATION: $locationUrl")

                // Fetch both the SSDP device descriptor AND the Roku device-info
                Thread {
                    try {
                        // First try the standard SSDP location (usually http://IP:8060/)
                        val url = URL(locationUrl)
                        val connection = url.openConnection() as HttpURLConnection
                        connection.connectTimeout = 3000
                        connection.readTimeout = 3000

                        if (connection.responseCode == 200) {
                            val xml = connection.inputStream.bufferedReader().use { it.readText() }
                            parseDeviceDescription(xml, sourceIp)
                        }
                        connection.disconnect()

                        // ALSO try the Roku-specific device-info endpoint for better names
                        try {
                            val rokuUrl = URL("http://$sourceIp:8060/query/device-info")
                            val rokuConn = rokuUrl.openConnection() as HttpURLConnection
                            rokuConn.connectTimeout = 3000
                            rokuConn.readTimeout = 3000

                            if (rokuConn.responseCode == 200) {
                                val rokuXml = rokuConn.inputStream.bufferedReader().use { it.readText() }
                                parseRokuDeviceInfo(rokuXml, sourceIp)
                            }
                            rokuConn.disconnect()
                        } catch (e: Exception) {
                            println("SSDP: Roku device-info fetch error: ${e.message}")
                        }

                    } catch (e: Exception) {
                        println("SSDP: Fetch error: ${e.message}")
                    }
                }.start()
            }
        } catch (e: Exception) {
            println("SSDP: Parse error: ${e.message}")
        }
    }

    private fun parseDeviceDescription(xml: String, sourceIp: String) {
        try {
            val friendlyNameRegex = Regex("<friendlyName>(.+?)</friendlyName>", RegexOption.IGNORE_CASE)
            val match = friendlyNameRegex.find(xml)

            if (match != null) {
                val friendlyName = match.groupValues[1].trim()
                println("SSDP: ✓ Found friendlyName '$friendlyName' at $sourceIp")

                synchronized(names) {
                    names[sourceIp] = friendlyName
                }
            } else {
                val manufacturerRegex = Regex("<manufacturer>(.+?)</manufacturer>", RegexOption.IGNORE_CASE)
                val modelRegex = Regex("<modelName>(.+?)</modelName>", RegexOption.IGNORE_CASE)

                val manufacturer = manufacturerRegex.find(xml)?.groupValues?.get(1)?.trim()
                val model = modelRegex.find(xml)?.groupValues?.get(1)?.trim()

                if (manufacturer != null || model != null) {
                    val name = listOfNotNull(manufacturer, model).joinToString(" ")
                    synchronized(names) {
                        names[sourceIp] = name
                    }
                    println("SSDP: ✓ Found '$name' at $sourceIp")
                }
            }
        } catch (e: Exception) {
            println("SSDP: XML parse error: ${e.message}")
        }
    }

    // NEW: Parse Roku-specific device-info XML for user-friendly names
    private fun parseRokuDeviceInfo(xml: String, sourceIp: String) {
        try {
            // Try user-device-name first (custom user name - THIS IS THE BEST ONE!)
            val userNameRegex = Regex("<user-device-name>(.+?)</user-device-name>", RegexOption.IGNORE_CASE)
            val userMatch = userNameRegex.find(xml)

            if (userMatch != null) {
                val userName = userMatch.groupValues[1].trim()
                println("SSDP: ✓✓ Found Roku user-device-name '$userName' at $sourceIp")
                synchronized(names) {
                    names[sourceIp] = userName
                }
                return
            }

            // Fall back to friendly-device-name
            val friendlyRegex = Regex("<friendly-device-name>(.+?)</friendly-device-name>", RegexOption.IGNORE_CASE)
            val friendlyMatch = friendlyRegex.find(xml)

            if (friendlyMatch != null) {
                val friendlyName = friendlyMatch.groupValues[1].trim()
                println("SSDP: ✓ Found Roku friendly-device-name '$friendlyName' at $sourceIp")
                synchronized(names) {
                    names[sourceIp] = friendlyName
                }
            }
        } catch (e: Exception) {
            println("SSDP: Roku XML parse error: ${e.message}")
        }
    }
}

// Keeping the same mDNS implementation
class MdnsActiveCache(context: Context) {
    val names = mutableMapOf<String, String>()
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null
    private var listenerThread: Thread? = null
    private var jmdnsThread: Thread? = null
    private var jmdns: JmDNS? = null
    @Volatile private var running = false

    fun start() {
        println("mDNS: Starting...")
        try {
            multicastLock = wifiManager.createMulticastLock("EasyIPScanner_mDNS")
            multicastLock?.acquire()

            running = true

            listenerThread = Thread {
                try {
                    val socket = MulticastSocket(5353)
                    val group = InetAddress.getByName("224.0.0.251")
                    socket.joinGroup(group)
                    socket.soTimeout = 1000

                    val buffer = ByteArray(2048)

                    while (running) {
                        try {
                            val packet = DatagramPacket(buffer, buffer.size)
                            socket.receive(packet)

                            val sourceIp = packet.address.hostAddress

                            if (sourceIp != null) {
                                val hostname = parseMdnsHostname(buffer.copyOf(packet.length))
                                if (hostname != null) {
                                    synchronized(names) {
                                        names[sourceIp] = hostname
                                    }
                                }
                            }
                        } catch (_: SocketTimeoutException) {
                        } catch (e: Exception) {
                        }
                    }

                    socket.leaveGroup(group)
                    socket.close()
                } catch (e: Exception) {
                }
            }
            listenerThread?.start()

            jmdnsThread = Thread {
                try {
                    jmdns = JmDNS.create()

                    val listener = object : ServiceListener {
                        override fun serviceAdded(event: ServiceEvent?) {
                            event?.let { jmdns?.requestServiceInfo(it.type, it.name, 1000) }
                        }

                        override fun serviceRemoved(event: ServiceEvent?) {}

                        override fun serviceResolved(event: ServiceEvent?) {
                            event?.info?.let { info ->
                                val addresses = info.inet4Addresses
                                if (addresses.isNotEmpty()) {
                                    val ip = addresses[0].hostAddress
                                    var deviceName: String? = null

                                    val server = info.server
                                    if (server != null && server.isNotBlank()) {
                                        deviceName = server.removeSuffix(".local").removeSuffix(".")
                                    }

                                    if (deviceName == null || deviceName.matches(Regex("[0-9a-fA-F-]{36}"))) {
                                        val serviceName = info.name
                                        if (serviceName != null && serviceName.isNotBlank() &&
                                            !serviceName.matches(Regex("[0-9a-fA-F-]{36}"))) {
                                            deviceName = serviceName
                                        }
                                    }

                                    if (ip != null && deviceName != null) {
                                        synchronized(names) {
                                            if (!names.containsKey(ip)) {
                                                names[ip] = deviceName
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    val serviceTypes = listOf(
                        "_http._tcp.local.", "_printer._tcp.local.", "_ipp._tcp.local.",
                        "_airplay._tcp.local.", "_raop._tcp.local.", "_spotify-connect._tcp.local."
                    )

                    for (type in serviceTypes) {
                        jmdns?.addServiceListener(type, listener)
                    }

                    while (running) {
                        Thread.sleep(1000)
                    }

                    jmdns?.close()
                } catch (e: Exception) {
                }
            }
            jmdnsThread?.start()

        } catch (e: Exception) {
        }
    }

    fun stop() {
        running = false
        listenerThread?.interrupt()
        jmdnsThread?.interrupt()
        try { jmdns?.close() } catch (_: Exception) {}
        multicastLock?.release()
    }

    private fun parseMdnsHostname(data: ByteArray): String? {
        if (data.size < 12) return null

        try {
            var pos = 12
            val qdcount = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
            val ancount = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)

            for (i in 0 until qdcount) {
                while (pos < data.size && data[pos].toInt() != 0) {
                    val len = data[pos].toInt() and 0xFF
                    if (len >= 0xC0) {
                        pos += 2
                        break
                    }
                    pos += len + 1
                }
                if (pos < data.size && data[pos].toInt() == 0) pos++
                pos += 4
            }

            val allNames = mutableListOf<String>()

            for (i in 0 until ancount) {
                if (pos >= data.size) break

                val name = readDnsName(data, pos)

                while (pos < data.size) {
                    val len = data[pos].toInt() and 0xFF
                    if (len == 0) {
                        pos++
                        break
                    }
                    if (len >= 0xC0) {
                        pos += 2
                        break
                    }
                    pos += len + 1
                }

                if (pos + 10 > data.size) break

                pos += 8
                val rdlength = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
                pos += 2

                if (name != null && name.endsWith(".local")) {
                    val hostname = name.removeSuffix(".local")
                    allNames.add(hostname)
                }

                pos += rdlength
            }

            return allNames.firstOrNull { hostname ->
                val isService = hostname.startsWith("_") || hostname.contains("._")
                val isUuid = hostname.matches(Regex("[0-9a-fA-F-]{36}"))
                !isService && !isUuid && hostname.length > 2
            }

        } catch (e: Exception) {
        }

        return null
    }

    private fun readDnsName(data: ByteArray, startPos: Int): String? {
        val parts = mutableListOf<String>()
        var pos = startPos
        var jumpCount = 0

        while (pos < data.size && jumpCount < 10) {
            val len = data[pos].toInt() and 0xFF

            if (len == 0) break

            if (len >= 0xC0) {
                if (pos + 1 >= data.size) break
                val offset = ((len and 0x3F) shl 8) or (data[pos + 1].toInt() and 0xFF)
                pos = offset
                jumpCount++
                continue
            }

            if (pos + 1 + len > data.size) break

            val part = String(data, pos + 1, len, Charsets.UTF_8)
            parts.add(part)
            pos += len + 1
        }

        return if (parts.isNotEmpty()) parts.joinToString(".") else null
    }
}