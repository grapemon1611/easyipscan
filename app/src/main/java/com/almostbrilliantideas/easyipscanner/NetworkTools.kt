package com.almostbrilliantideas.easyipscanner

import android.content.Context
import java.net.*

data class SpeedTestResult(
    val speedMbps: Double,
    val detailedOutput: String
)

suspend fun testLanSpeedWithLiveOutput(
    context: Context,
    onUpdate: (String) -> Unit
): SpeedTestResult? {
    return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            onUpdate("🔍 Detecting gateway...")

            val gateway = detectBestCidr(context)?.substringBefore("/")?.let { cidr ->
                val parts = cidr.split(".")
                if (parts.size == 4) {
                    "${parts[0]}.${parts[1]}.${parts[2]}.1"
                } else null
            } ?: run {
                onUpdate("❌ Could not detect gateway")
                return@withContext null
            }

            onUpdate("✓ Gateway detected: $gateway\n🔍 Finding responsive port...")

            val port = listOf(80, 443, 8080, 53).firstOrNull { testPort ->
                try {
                    Socket().use { socket ->
                        socket.soTimeout = 1000
                        socket.connect(InetSocketAddress(gateway, testPort), 1000)
                        true
                    }
                } catch (e: Exception) {
                    false
                }
            } ?: run {
                onUpdate("❌ No responsive ports found on gateway")
                return@withContext null
            }

            onUpdate("✓ Connected to port $port\n\n📊 Starting 10-second speed test...\n")
            kotlinx.coroutines.delay(500)

            val testDurationMs = 10000L
            val bufferSize = 64 * 1024
            val buffer = ByteArray(bufferSize)

            var totalBytesSent = 0L
            val startTime = System.currentTimeMillis()
            var lastUpdateTime = startTime

            Socket().use { socket ->
                socket.soTimeout = 0
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(gateway, port), 2000)

                val output = socket.getOutputStream()

                while (System.currentTimeMillis() - startTime < testDurationMs) {
                    try {
                        output.write(buffer)
                        totalBytesSent += bufferSize

                        val now = System.currentTimeMillis()
                        if (now - lastUpdateTime >= 500) {
                            val elapsedSec = (now - startTime) / 1000.0
                            val megabits = (totalBytesSent * 8) / 1_000_000.0
                            val currentMbps = megabits / elapsedSec
                            val dataSentMB = totalBytesSent / 1_000_000.0

                            onUpdate(
                                "✓ Port $port connected\n" +
                                        "\n📊 Testing... ${String.format("%.1f", elapsedSec)}s / 10s\n" +
                                        "━━━━━━━━━━━━━━━━━━━━\n" +
                                        "Data sent:    ${String.format("%.1f", dataSentMB)} MB\n" +
                                        "Speed:        ${String.format("%.1f", currentMbps)} Mbps\n" +
                                        "━━━━━━━━━━━━━━━━━━━━"
                            )

                            lastUpdateTime = now
                        }
                    } catch (e: Exception) {
                        break
                    }
                }
            }

            val totalElapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
            val totalMegabits = (totalBytesSent * 8) / 1_000_000.0
            val finalMbps = totalMegabits / totalElapsedSec
            val totalMB = totalBytesSent / 1_000_000.0

            val detailedOutput = buildString {
                appendLine("✅ TEST COMPLETE")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()
                appendLine("Gateway:      $gateway")
                appendLine("Port:         $port")
                appendLine("Duration:     ${String.format("%.2f", totalElapsedSec)}s")
                appendLine()
                appendLine("Data Sent:    ${String.format("%.2f", totalMB)} MB")
                appendLine("Throughput:   ${String.format("%.1f", finalMbps)} Mbps")
                appendLine()
                appendLine("Rating:       ${getRating(finalMbps)}")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()

                when {
                    finalMbps >= 100 -> {
                        appendLine("💚 Excellent LAN performance!")
                        appendLine("   Your local network is working great.")
                    }
                    finalMbps >= 50 -> {
                        appendLine("✅ Good LAN performance")
                        appendLine("   Suitable for most tasks including HD streaming.")
                    }
                    finalMbps >= 25 -> {
                        appendLine("⚠️  Fair LAN performance")
                        appendLine("   Consider:")
                        appendLine("   • Check WiFi signal strength")
                        appendLine("   • Try 5GHz band if available")
                        appendLine("   • Test with Ethernet cable")
                    }
                    else -> {
                        appendLine("🔴 Poor LAN performance")
                        appendLine("   Possible issues:")
                        appendLine("   • Weak WiFi signal")
                        appendLine("   • Network congestion")
                        appendLine("   • Bad cable/switch")
                        appendLine("   • Router overload")
                    }
                }
            }

            SpeedTestResult(finalMbps, detailedOutput)
        } catch (e: Exception) {
            onUpdate("❌ Error: ${e.message}")
            null
        }
    }
}

suspend fun testWanSpeedWithLiveOutput(
    onUpdate: (String) -> Unit
): SpeedTestResult? {
    return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            onUpdate("🔍 Connecting to speed test server...")

            val testUrl = "https://speed.cloudflare.com/__down?bytes=100000000"

            onUpdate("✓ Connected to Cloudflare\n\n📊 Starting download test...\n")
            kotlinx.coroutines.delay(500)

            val startTime = System.currentTimeMillis()
            var totalBytesReceived = 0L
            var lastUpdateTime = startTime
            val testDurationMs = 10000L

            try {
                val url = java.net.URL(testUrl)
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 15000
                connection.requestMethod = "GET"

                connection.connect()

                val inputStream = connection.inputStream
                val buffer = ByteArray(8192)
                var bytesRead: Int

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    totalBytesReceived += bytesRead

                    val now = System.currentTimeMillis()

                    if (now - startTime >= testDurationMs) {
                        break
                    }

                    if (now - lastUpdateTime >= 500) {
                        val elapsedSec = (now - startTime) / 1000.0
                        val megabits = (totalBytesReceived * 8) / 1_000_000.0
                        val currentMbps = megabits / elapsedSec
                        val dataMB = totalBytesReceived / 1_000_000.0

                        onUpdate(
                            "✓ Cloudflare connected\n" +
                                    "\n📊 Download test... ${String.format("%.1f", elapsedSec)}s / 10s\n" +
                                    "━━━━━━━━━━━━━━━━━━━━\n" +
                                    "Downloaded:   ${String.format("%.1f", dataMB)} MB\n" +
                                    "Speed:        ${String.format("%.1f", currentMbps)} Mbps\n" +
                                    "━━━━━━━━━━━━━━━━━━━━"
                        )

                        lastUpdateTime = now
                    }
                }

                inputStream.close()
                connection.disconnect()

            } catch (e: Exception) {
                onUpdate("❌ Download test failed: ${e.message}")
                return@withContext null
            }

            val totalElapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
            val totalMegabits = (totalBytesReceived * 8) / 1_000_000.0
            val downloadMbps = totalMegabits / totalElapsedSec
            val totalMB = totalBytesReceived / 1_000_000.0

            val detailedOutput = buildString {
                appendLine("✅ TEST COMPLETE")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()
                appendLine("Server:       Cloudflare")
                appendLine("Duration:     ${String.format("%.2f", totalElapsedSec)}s")
                appendLine()
                appendLine("Downloaded:   ${String.format("%.2f", totalMB)} MB")
                appendLine("Speed:        ${String.format("%.1f", downloadMbps)} Mbps")
                appendLine()
                appendLine("Rating:       ${getWanRating(downloadMbps)}")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()

                when {
                    downloadMbps >= 100 -> {
                        appendLine("💚 Excellent internet speed!")
                        appendLine("   Great for 4K streaming, large downloads.")
                    }
                    downloadMbps >= 50 -> {
                        appendLine("✅ Good internet speed")
                        appendLine("   Suitable for HD streaming, video calls.")
                    }
                    downloadMbps >= 25 -> {
                        appendLine("⚠️  Fair internet speed")
                        appendLine("   May experience buffering on HD video.")
                        appendLine("   Consider:")
                        appendLine("   • Contact ISP about plan upgrade")
                        appendLine("   • Check for ISP outages")
                    }
                    else -> {
                        appendLine("🔴 Poor internet speed")
                        appendLine("   Possible issues:")
                        appendLine("   • ISP congestion")
                        appendLine("   • Service plan limitation")
                        appendLine("   • Modem/router issue")
                        appendLine("   • Contact your ISP")
                    }
                }
            }

            SpeedTestResult(downloadMbps, detailedOutput)
        } catch (e: Exception) {
            onUpdate("❌ Error: ${e.message}")
            null
        }
    }
}

fun buildComparisonDiagnosis(lanMbps: Double, wanMbps: Double): String {
    return buildString {
        appendLine("═══════════════════════════════")
        appendLine("COMPLETE DIAGNOSIS")
        appendLine("═══════════════════════════════")
        appendLine()
        appendLine("LAN Speed:    ${String.format("%.1f", lanMbps)} Mbps  ${getRating(lanMbps)}")
        appendLine("WAN Speed:    ${String.format("%.1f", wanMbps)} Mbps  ${getWanRating(wanMbps)}")
        appendLine()

        val diff = kotlin.math.abs(lanMbps - wanMbps)
        val percentDiff = (diff / kotlin.math.max(lanMbps, wanMbps)) * 100

        when {
            percentDiff < 20 -> {
                if (lanMbps >= 50 && wanMbps >= 50) {
                    appendLine("✅ EXCELLENT")
                    appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    appendLine("Both local network and internet")
                    appendLine("are performing well. No issues")
                    appendLine("detected.")
                } else {
                    appendLine("⚠️  BOTH SLOW")
                    appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    appendLine("Both LAN and WAN speeds are low")
                    appendLine("and similar. This suggests:")
                    appendLine()
                    appendLine("Likely cause:")
                    appendLine("• Device WiFi limitation")
                    appendLine("• Phone in power-saving mode")
                    appendLine()
                    appendLine("Try:")
                    appendLine("• Test with different device")
                    appendLine("• Use Ethernet if possible")
                    appendLine("• Disable power saving")
                }
            }

            lanMbps > wanMbps + 20 -> {
                appendLine("🌐 INTERNET BOTTLENECK")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine("Local network is ${String.format("%.0f", lanMbps - wanMbps)} Mbps faster")
                appendLine("than internet connection.")
                appendLine()
                appendLine("This is normal and expected.")
                appendLine("Your internet speed is limited by")
                appendLine("your ISP plan, not local network.")
                appendLine()
                if (wanMbps < 25) {
                    appendLine("Consider:")
                    appendLine("• Upgrading ISP plan")
                    appendLine("• Checking for ISP outages")
                }
            }

            wanMbps > lanMbps + 20 -> {
                appendLine("📡 LOCAL NETWORK BOTTLENECK")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine("Internet is ${String.format("%.0f", wanMbps - lanMbps)} Mbps faster")
                appendLine("than local network speed!")
                appendLine()
                appendLine("⚠️  Your local network is limiting")
                appendLine("your internet speed. You're not")
                appendLine("getting full speed from your ISP.")
                appendLine()
                appendLine("Likely causes:")
                appendLine("• WiFi interference/congestion")
                appendLine("• Outdated router (not WiFi 6)")
                appendLine("• 2.4GHz band (use 5GHz)")
                appendLine("• Distance from router")
                appendLine("• Bad Ethernet cable")
                appendLine()
                appendLine("Fixes:")
                appendLine("• Move closer to router")
                appendLine("• Switch to 5GHz WiFi")
                appendLine("• Upgrade to WiFi 6 router")
                appendLine("• Use wired Ethernet")
            }
        }

        appendLine()
        appendLine("═══════════════════════════════")
    }
}

fun getRating(speedMbps: Double): String {
    return when {
        speedMbps >= 100 -> "✓ Excellent"
        speedMbps >= 50 -> "✓ Good"
        speedMbps >= 25 -> "~ Fair"
        else -> "⚠ Poor"
    }
}

fun getRatingColor(speedMbps: Double): androidx.compose.ui.graphics.Color {
    return when {
        speedMbps >= 50 -> androidx.compose.ui.graphics.Color(0xFF1B5E20)
        speedMbps >= 25 -> androidx.compose.ui.graphics.Color(0xFFF57C00)
        else -> androidx.compose.ui.graphics.Color(0xFFB71C1C)
    }
}

fun getWanRating(speedMbps: Double): String {
    return when {
        speedMbps >= 100 -> "✓ Excellent"
        speedMbps >= 50 -> "✓ Good"
        speedMbps >= 25 -> "~ Fair"
        else -> "⚠ Poor"
    }
}

fun getWanRatingColor(speedMbps: Double): androidx.compose.ui.graphics.Color {
    return when {
        speedMbps >= 50 -> androidx.compose.ui.graphics.Color(0xFF1B5E20)
        speedMbps >= 25 -> androidx.compose.ui.graphics.Color(0xFFF57C00)
        else -> androidx.compose.ui.graphics.Color(0xFFB71C1C)
    }
}

fun pingHost(host: String): String {
    systemPing(host, 1, 1000)?.let { return it }
    if (tcpProbe(host, 80, 300)) return "TCP probe to $host:80 succeeded (host likely up)."
    if (tcpProbe(host, 443, 300)) return "TCP probe to $host:443 succeeded (host likely up)."
    return "No response from $host (ICMP blocked or host down)."
}