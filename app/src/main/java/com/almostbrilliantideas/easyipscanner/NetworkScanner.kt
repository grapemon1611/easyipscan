package com.almostbrilliantideas.easyipscanner

import android.content.Context
import android.net.DhcpInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.*
import kotlin.math.max

data class ScanResult(
    val ip: String,
    val status: String,
    val details: String = "",
    val latencyMs: Long? = null,
    val hostname: String? = null
)

// Main CIDR scanning function
fun scanCidr(
    cidr: String,
    concurrency: Int,
    timeoutMs: Int,
    mdns: MdnsActiveCache,
    ssdp: SsdpDiscovery,
    db: DeviceDatabase,
    onResult: (ScanResult) -> Unit
) {
    val parsed = parseCidrRange(cidr)
    if (parsed == null) {
        // Check if it's a network size issue
        val parts = cidr.split("/")
        if (parts.size == 2) {
            val prefix = parts[1].toIntOrNull()
            if (prefix != null && prefix < 22) {
                onResult(ScanResult(
                    cidr,
                    "Network too large",
                    "EasyIP Scan supports networks up to /22 (1,022 devices). Your /$prefix network has ${
                        when(prefix) {
                            21 -> "2,046"
                            20 -> "4,094"
                            19 -> "8,190"
                            18 -> "16,382"
                            17 -> "32,766"
                            16 -> "65,534"
                            else -> "too many"
                        }
                    } potential hosts. For enterprise-scale networks, please use desktop scanning tools."
                ))
                return
            }
        }
        onResult(ScanResult(cidr, "Invalid CIDR", "Use format like 10.0.0.0/24"))
        return
    }
    val (_, first, last) = parsed

    // Capture scan start time BEFORE we start discovering devices
    val scanStartTime = System.currentTimeMillis()

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

                        // Save to database
                        val vendor = extractVendorFromDeviceNames(allNames)
                        val currentTime = System.currentTimeMillis()
                        db.upsertDevice(ip, allNames, vendor, currentTime)

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

                                // Save to database
                                val vendor = extractVendorFromDeviceNames(allNames)
                                val currentTime = System.currentTimeMillis()
                                db.upsertDevice(ip, allNames, vendor, currentTime)

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

    // Mark devices not seen in this scan as offline
    // Use scanStartTime so devices discovered DURING this scan are not marked offline
    val offlineCount = db.markOfflineDevicesNotSeenSince(scanStartTime)
    println("Scan complete: Marked $offlineCount device(s) as offline")

    parent.cancel()
}

// Parse CIDR range into network, first, and last IP
fun parseCidrRange(cidr: String): Triple<Int, Int, Int>? {
    val parts = cidr.split("/")
    if (parts.size != 2) return null
    val base = ipToInt(parts[0]) ?: return null
    val prefix = parts[1].toIntOrNull() ?: return null
    if (prefix !in 1..32) return null

    // Hard limit: refuse to scan networks larger than /22 (1,022 hosts)
    if (prefix < 22) return null

    val mask = if (prefix == 32) -1 else (-1 shl (32 - prefix))
    val network = base and mask
    val broadcast = network or mask.inv()
    val first = if (prefix == 32) network else network + 1
    val last = if (prefix == 32) network else broadcast - 1
    return Triple(network, first, last)
}

// Detect best CIDR for current network
fun detectBestCidr(ctx: Context): String? {
    val detected = getWifiCidr(ctx) ?: getInterfaceCidr()
    println("CIDR Detection: $detected")
    return detected
}

// Get CIDR from WiFi manager
fun getWifiCidr(ctx: Context): String? {
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

// Get CIDR from network interfaces
fun getInterfaceCidr(): String? {
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

// Normalize IP to network address
fun normalizeToNetworkCidr(candidate: String, fallback: String): String {
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

// System ping via command line
fun systemPing(host: String, count: Int, timeoutMs: Int): String? {
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

// Single ping for ping tool
fun systemPingSingle(host: String, timeoutMs: Int): String? {
    return try {
        val process = ProcessBuilder(
            "/system/bin/ping", "-c", "1",
            "-W", max(1, timeoutMs / 1000).toString(), host
        ).redirectErrorStream(true).start()
        val code = process.waitFor()
        if (code == 0) "success" else null
    } catch (_: Exception) {
        null
    }
}

// TCP probe to check if port is open
fun tcpProbe(host: String, port: Int, timeoutMs: Int): Boolean {
    return try {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), timeoutMs)
            true
        }
    } catch (_: Exception) {
        false
    }
}

// IP address utilities
fun ipToInt(ip: String): Int? {
    val parts = ip.split(".").mapNotNull { it.toIntOrNull() }
    if (parts.size != 4) return null
    for (p in parts) if (p !in 0..255) return null
    return (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
}

fun intToIp(v: Int): String =
    "${(v ushr 24) and 0xFF}.${(v ushr 16) and 0xFF}.${(v ushr 8) and 0xFF}.${v and 0xFF}"