package com.almostbrilliantideas.easyipscanner

import android.content.Context
import android.util.Log
import java.net.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request

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
            val serverName = "EasyIPScan"
            val baseUrl = "https://speed.easyipscan.app"

            onUpdate("🔍 Connecting to speed test server...")
            Log.d("WanSpeedTest", "Starting WAN speed test...")

            // ============ LATENCY TEST ============
            onUpdate("📡 Testing latency...")
            Log.d("WanSpeedTest", "Starting latency test...")

            var latencyMs: Long = -1
            try {
                val pingTimes = mutableListOf<Long>()
                repeat(5) { attempt ->
                    val pingStart = System.currentTimeMillis()
                    val pingUrl = java.net.URL("$baseUrl/ping")
                    val pingConnection = pingUrl.openConnection() as java.net.HttpURLConnection
                    pingConnection.connectTimeout = 5000
                    pingConnection.readTimeout = 5000
                    pingConnection.requestMethod = "GET"
                    pingConnection.setRequestProperty("User-Agent", "EasyIPScan/1.0")
                    pingConnection.connect()
                    val responseCode = pingConnection.responseCode
                    pingConnection.disconnect()

                    if (responseCode == 204 || responseCode == 200) {
                        val pingTime = System.currentTimeMillis() - pingStart
                        pingTimes.add(pingTime)
                        Log.d("WanSpeedTest", "Ping $attempt: ${pingTime}ms")
                    }
                    kotlinx.coroutines.delay(100)
                }

                if (pingTimes.isNotEmpty()) {
                    latencyMs = pingTimes.sorted().let { sorted ->
                        // Use median for more accurate latency
                        sorted[sorted.size / 2]
                    }
                    Log.d("WanSpeedTest", "Median latency: ${latencyMs}ms")
                }
            } catch (e: Exception) {
                Log.e("WanSpeedTest", "Latency test failed: ${e.message}", e)
                // Continue with download test even if latency fails
            }

            val latencyDisplay = if (latencyMs > 0) "${latencyMs}ms" else "N/A"
            onUpdate("✓ Latency: $latencyDisplay\n\n📊 Starting download test...\n")
            kotlinx.coroutines.delay(300)

            // ============ DOWNLOAD TEST ============
            val downloadUrl = "$baseUrl/download/10MB.bin"
            Log.d("WanSpeedTest", "Download URL: $downloadUrl")

            val numConnections = 8
            val testDurationMs = 10000L
            val totalBytesReceived = AtomicLong(0L)
            val connectionBytes = Array(numConnections) { AtomicLong(0L) }
            val stopFlag = AtomicBoolean(false)
            var downloadStartTime = 0L

            // OkHttp client with connection pooling for 8 parallel connections
            val client = OkHttpClient.Builder()
                .connectionPool(ConnectionPool(numConnections, 30, TimeUnit.SECONDS))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "EasyIPScan/1.0")
                .build()

            Log.d("WanSpeedTest", "Starting $numConnections parallel download connections...")
            onUpdate("✓ Latency: $latencyDisplay\n\n📥 Starting $numConnections parallel downloads...\n")

            try {
                coroutineScope {
                    // Start time after connections are initiated
                    downloadStartTime = System.currentTimeMillis()

                    // Launch progress updater
                    val progressJob = async {
                        var lastUpdateTime = downloadStartTime
                        while (!stopFlag.get()) {
                            kotlinx.coroutines.delay(500)
                            val now = System.currentTimeMillis()
                            val elapsed = now - downloadStartTime

                            if (elapsed >= testDurationMs) {
                                stopFlag.set(true)
                                break
                            }

                            val bytes = totalBytesReceived.get()
                            val elapsedSec = elapsed / 1000.0
                            val megabits = (bytes * 8) / 1_000_000.0
                            val currentMbps = megabits / elapsedSec
                            val dataMB = bytes / 1_000_000.0

                            onUpdate(
                                "✓ Latency: $latencyDisplay\n" +
                                        "\n📥 Download test... ${String.format("%.1f", elapsedSec)}s / 10s\n" +
                                        "━━━━━━━━━━━━━━━━━━━━\n" +
                                        "Connections:  $numConnections parallel\n" +
                                        "Downloaded:   ${String.format("%.1f", dataMB)} MB\n" +
                                        "Speed:        ${String.format("%.1f", currentMbps)} Mbps\n" +
                                        "━━━━━━━━━━━━━━━━━━━━"
                            )

                            // Log per-connection stats periodically
                            val perConnBytes = connectionBytes.mapIndexed { i, ab ->
                                "C$i: ${String.format("%.1f", ab.get() / 1_000_000.0)}MB"
                            }.joinToString(", ")
                            Log.d("WanSpeedTest", "Progress: ${String.format("%.1f", elapsedSec)}s, $perConnBytes, Total: ${String.format("%.1f", dataMB)}MB, Speed: ${String.format("%.1f", currentMbps)} Mbps")
                            Log.d("WanSpeedTest", "Connection pool: ${client.connectionPool.connectionCount()} total, ${client.connectionPool.idleConnectionCount()} idle")

                            lastUpdateTime = now
                        }
                    }

                    // Launch 4 parallel download workers
                    val downloadJobs = (0 until numConnections).map { connectionId ->
                        async {
                            val buffer = ByteArray(64 * 1024)
                            var iterationCount = 0

                            Log.d("WanSpeedTest", "Connection $connectionId: Starting")

                            while (!stopFlag.get()) {
                                iterationCount++
                                try {
                                    val callStart = System.currentTimeMillis()
                                    val response = client.newCall(request).execute()
                                    val callTime = System.currentTimeMillis() - callStart

                                    if (iterationCount == 1) {
                                        response.handshake?.let { handshake ->
                                            Log.d("WanSpeedTest", "Connection $connectionId: TLS ${handshake.tlsVersion}, setup ${callTime}ms")
                                        }
                                    } else {
                                        Log.d("WanSpeedTest", "Connection $connectionId: Iteration $iterationCount, request ${callTime}ms (reused)")
                                    }

                                    if (!response.isSuccessful) {
                                        Log.e("WanSpeedTest", "Connection $connectionId: HTTP error ${response.code}")
                                        response.close()
                                        break
                                    }

                                    val inputStream = response.body?.byteStream()
                                    if (inputStream == null) {
                                        Log.e("WanSpeedTest", "Connection $connectionId: null body")
                                        response.close()
                                        break
                                    }

                                    var bytesRead: Int = 0
                                    while (!stopFlag.get()) {
                                        bytesRead = inputStream.read(buffer)
                                        if (bytesRead == -1) break

                                        totalBytesReceived.addAndGet(bytesRead.toLong())
                                        connectionBytes[connectionId].addAndGet(bytesRead.toLong())

                                        // Check time limit
                                        if (System.currentTimeMillis() - downloadStartTime >= testDurationMs) {
                                            stopFlag.set(true)
                                            break
                                        }
                                    }

                                    response.close()

                                } catch (e: Exception) {
                                    if (!stopFlag.get()) {
                                        Log.e("WanSpeedTest", "Connection $connectionId: Error - ${e.javaClass.simpleName}: ${e.message}")
                                    }
                                    break
                                }
                            }

                            val connMB = connectionBytes[connectionId].get() / 1_000_000.0
                            Log.d("WanSpeedTest", "Connection $connectionId: Finished after $iterationCount iterations, ${String.format("%.2f", connMB)} MB")
                            connectionBytes[connectionId].get()
                        }
                    }

                    // Wait for all downloads to complete
                    downloadJobs.awaitAll()
                    stopFlag.set(true)
                    progressJob.cancel()
                }

                val totalBytes = totalBytesReceived.get()
                val totalMB = totalBytes / 1_000_000.0
                Log.d("WanSpeedTest", "========== DOWNLOAD TEST SUMMARY ==========")
                Log.d("WanSpeedTest", "Parallel connections: $numConnections")
                connectionBytes.forEachIndexed { i, ab ->
                    Log.d("WanSpeedTest", "  Connection $i: ${String.format("%.2f", ab.get() / 1_000_000.0)} MB")
                }
                Log.d("WanSpeedTest", "Total bytes: $totalBytes (${String.format("%.2f", totalMB)} MB)")
                Log.d("WanSpeedTest", "===========================================")

            } catch (e: java.net.UnknownHostException) {
                Log.e("WanSpeedTest", "DNS resolution failed", e)
                onUpdate("❌ DNS Error: Cannot resolve server\nCheck internet connection")
                return@withContext null
            } catch (e: java.net.SocketTimeoutException) {
                Log.e("WanSpeedTest", "Connection timed out", e)
                onUpdate("❌ Connection timed out\nServer may be unreachable")
                return@withContext null
            } catch (e: SSLException) {
                Log.e("WanSpeedTest", "SSL/TLS error", e)
                onUpdate("❌ SSL Error: ${e.message}\nCheck device date/time settings")
                return@withContext null
            } catch (e: java.net.ConnectException) {
                Log.e("WanSpeedTest", "Connection refused", e)
                onUpdate("❌ Connection refused\nFirewall may be blocking")
                return@withContext null
            } catch (e: java.io.IOException) {
                Log.e("WanSpeedTest", "IO error during download", e)
                onUpdate("❌ Network error: ${e.message}")
                return@withContext null
            } catch (e: Exception) {
                Log.e("WanSpeedTest", "Unexpected error: ${e.javaClass.simpleName}", e)
                onUpdate("❌ Download test failed: ${e.javaClass.simpleName}\n${e.message}")
                return@withContext null
            } finally {
                client.connectionPool.evictAll()
            }

            // Safety check: ensure we received data before calculating
            val totalBytes = totalBytesReceived.get()
            if (totalBytes == 0L) {
                onUpdate("❌ No data received from server")
                return@withContext null
            }

            val downloadElapsedSec = (System.currentTimeMillis() - downloadStartTime) / 1000.0
            val downloadMegabits = (totalBytes * 8) / 1_000_000.0
            val downloadMbps = downloadMegabits / downloadElapsedSec
            val downloadMB = totalBytes / 1_000_000.0

            // ============ UPLOAD TEST ============
            onUpdate(
                "✓ Latency: $latencyDisplay\n" +
                "✓ Download: ${String.format("%.1f", downloadMbps)} Mbps\n" +
                "\n📤 Starting upload test...\n"
            )
            kotlinx.coroutines.delay(300)

            var uploadMbps = 0.0
            var uploadMB = 0.0
            val uploadUrl = "$baseUrl/upload"
            Log.d("WanSpeedTest", "Starting upload test to: $uploadUrl")

            try {
                val uploadStartTime = System.currentTimeMillis()
                var totalBytesSent = 0L
                var uploadLastUpdateTime = uploadStartTime
                val uploadTestDurationMs = 10000L
                val uploadBuffer = ByteArray(64 * 1024) // 64KB chunks

                val url = java.net.URL(uploadUrl)
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 15000
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("User-Agent", "EasyIPScan/1.0")
                connection.setRequestProperty("Content-Type", "application/octet-stream")
                connection.setChunkedStreamingMode(uploadBuffer.size)

                Log.d("WanSpeedTest", "Opening upload connection...")
                val outputStream = connection.outputStream

                while (System.currentTimeMillis() - uploadStartTime < uploadTestDurationMs) {
                    try {
                        outputStream.write(uploadBuffer)
                        totalBytesSent += uploadBuffer.size

                        val now = System.currentTimeMillis()
                        if (now - uploadLastUpdateTime >= 500) {
                            val elapsedSec = (now - uploadStartTime) / 1000.0
                            val megabits = (totalBytesSent * 8) / 1_000_000.0
                            val currentMbps = megabits / elapsedSec
                            val dataMB = totalBytesSent / 1_000_000.0

                            onUpdate(
                                "✓ Latency: $latencyDisplay\n" +
                                "✓ Download: ${String.format("%.1f", downloadMbps)} Mbps\n" +
                                "\n📤 Upload test... ${String.format("%.1f", elapsedSec)}s / 10s\n" +
                                "━━━━━━━━━━━━━━━━━━━━\n" +
                                "Uploaded:     ${String.format("%.1f", dataMB)} MB\n" +
                                "Speed:        ${String.format("%.1f", currentMbps)} Mbps\n" +
                                "━━━━━━━━━━━━━━━━━━━━"
                            )

                            uploadLastUpdateTime = now
                        }
                    } catch (e: Exception) {
                        Log.w("WanSpeedTest", "Upload write error: ${e.message}")
                        break
                    }
                }

                outputStream.flush()
                outputStream.close()

                // Read server response (if any)
                try {
                    val responseCode = connection.responseCode
                    Log.d("WanSpeedTest", "Upload response code: $responseCode")
                } catch (e: Exception) {
                    Log.w("WanSpeedTest", "Could not read upload response: ${e.message}")
                }

                connection.disconnect()

                val uploadElapsedSec = (System.currentTimeMillis() - uploadStartTime) / 1000.0
                val uploadMegabits = (totalBytesSent * 8) / 1_000_000.0
                uploadMbps = uploadMegabits / uploadElapsedSec
                uploadMB = totalBytesSent / 1_000_000.0
                Log.d("WanSpeedTest", "Upload complete. Total bytes: $totalBytesSent, Speed: ${String.format("%.1f", uploadMbps)} Mbps")

            } catch (e: Exception) {
                Log.e("WanSpeedTest", "Upload test failed: ${e.message}", e)
                // Continue without upload results
            }

            val uploadDisplay = if (uploadMbps > 0) "${String.format("%.1f", uploadMbps)} Mbps" else "N/A"

            val detailedOutput = buildString {
                appendLine("✅ TEST COMPLETE")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()
                appendLine("Server:       $serverName")
                appendLine()
                appendLine("Latency:      $latencyDisplay")
                appendLine("Download:     ${String.format("%.1f", downloadMbps)} Mbps")
                appendLine("Upload:       $uploadDisplay")
                appendLine()
                appendLine("Downloaded:   ${String.format("%.2f", downloadMB)} MB")
                if (uploadMbps > 0) {
                    appendLine("Uploaded:     ${String.format("%.2f", uploadMB)} MB")
                }
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
            Log.e("WanSpeedTest", "Outer exception: ${e.javaClass.simpleName}", e)
            onUpdate("❌ Error: ${e.javaClass.simpleName}\n${e.message}")
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