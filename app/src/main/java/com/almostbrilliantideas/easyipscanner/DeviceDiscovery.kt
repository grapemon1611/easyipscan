package com.almostbrilliantideas.easyipscanner

import kotlinx.coroutines.*
import java.net.*

// Container for all discovered names from different sources
data class DeviceNames(
    val rokuHttp: String? = null,
    val ssdp: String? = null,
    val mdns: String? = null,
    val netbios: String? = null,
    val dns: String? = null,
    val httpServer: String? = null,
    val deviceType: String? = null
) {
    fun getBestName(): String? {
        ssdp?.let { if (it.isUserFriendly()) return it }
        rokuHttp?.let { if (it.isUserFriendly()) return it }

        if (deviceType != null && httpServer != null) {
            val manufacturer = extractManufacturer(httpServer)
            if (manufacturer != null && manufacturer != "*" && manufacturer.length >= 2) {
                return "$manufacturer $deviceType"
            }
        }

        netbios?.let { if (it.isUserFriendly()) return it }
        mdns?.let { if (it.isUserFriendly()) return it }
        dns?.let { if (it.isUserFriendly()) return it }

        return ssdp ?: rokuHttp ?: mdns ?: netbios ?: dns
    }

    private fun extractManufacturer(serverHeader: String): String? {
        val parts = serverHeader.split(Regex("[-_/\\s]"))
        return parts.firstOrNull()?.takeIf { it.length >= 2 }
    }

    private fun String.isUserFriendly(): Boolean {
        val cleaned = this.removeSuffix(".local").removeSuffix(".lan")

        val isSerialNumber = cleaned.matches(Regex("X[0-9A-Z]{8,}")) ||
                cleaned.matches(Regex("[0-9A-F]{12}")) ||
                cleaned.matches(Regex("[0-9A-F]{14,}")) ||
                cleaned.matches(Regex(".*-[0-9a-f]{6}$")) ||
                cleaned.matches(Regex("[A-Z]{2}[0-9A-F]{10,}")) ||
                cleaned.matches(Regex("^[0-9]+-[0-9]+-[0-9]+$"))

        return cleaned.length >= 3 && !isSerialNumber
    }

    fun toDebugString(): String {
        return buildString {
            append("SSDP:$ssdp ")
            append("Roku:$rokuHttp ")
            append("mDNS:$mdns ")
            append("NetBIOS:$netbios ")
            append("DNS:$dns ")
            append("HTTP:$httpServer ")
            append("Type:$deviceType")
        }
    }
}

data class PortBannerResult(
    val httpServer: String? = null,
    val deviceType: String? = null,
    val discoveredName: String? = null
)

fun discoverAllNames(ip: String, timeoutMs: Int = 2000): DeviceNames {
    val names = mutableMapOf<String, String?>()
    var portBannerResult: PortBannerResult? = null

    runBlocking {
        val jobs = listOf(
            async(Dispatchers.IO) {
                try {
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
            },
            async(Dispatchers.IO) {
                try {
                    names["mdns_active"] = mdnsQuery(ip, timeoutMs)
                    println("$ip mDNS query: ${names["mdns_active"]}")
                } catch (e: Exception) {
                    println("$ip mDNS query failed: ${e.message}")
                }
            },
            async(Dispatchers.IO) {
                try {
                    portBannerResult = portScanWithBanner(ip, timeoutMs)
                    println("$ip Port scan: ${portBannerResult?.discoveredName}")
                } catch (e: Exception) {
                    println("$ip Port scan failed: ${e.message}")
                }
            }
        )
        jobs.joinAll()
    }

    return DeviceNames(
        rokuHttp = names["roku"],
        netbios = names["netbios"],
        dns = names["dns"],
        mdns = names["mdns_active"] ?: portBannerResult?.discoveredName,
        httpServer = portBannerResult?.httpServer,
        deviceType = portBannerResult?.deviceType
    )
}

fun extractVendorFromDeviceNames(names: DeviceNames): String? {
    if (names.httpServer == null) return null
    if (names.httpServer == "******" || names.httpServer == "*") return null

    val parts = names.httpServer.split(Regex("[-_/\\s]"))
    val vendor = parts.firstOrNull()?.takeIf { it.length >= 2 }

    return vendor
}

fun getRokuName(ip: String, timeoutMs: Int = 1500): String? {
    return try {
        val url = URL("http://$ip:8060/query/device-info")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs

        val code = conn.responseCode
        if (code == 200) {
            val xml = conn.inputStream.bufferedReader().use { it.readText() }

            val userNameMatch = Regex("<user-device-name>(.+?)</user-device-name>", RegexOption.IGNORE_CASE).find(xml)
            if (userNameMatch != null) {
                return userNameMatch.groupValues[1].trim()
            }

            val friendlyMatch = Regex("<friendly-device-name>(.+?)</friendly-device-name>", RegexOption.IGNORE_CASE).find(xml)
            if (friendlyMatch != null) {
                return friendlyMatch.groupValues[1].trim()
            }

            val modelMatch = Regex("<model-name>(.+?)</model-name>", RegexOption.IGNORE_CASE).find(xml)
            return modelMatch?.groupValues?.get(1)?.trim()
        }
        conn.disconnect()
        null
    } catch (e: Exception) {
        null
    }
}

fun reverseDns(ip: String): String? {
    return try {
        val parts = ip.split('.').map { it.toInt().toByte() }.toByteArray()
        val addr = InetAddress.getByAddress(parts)
        val name = addr.canonicalHostName
        if (name == addr.hostAddress) null else name
    } catch (_: Exception) {
        null
    }
}

fun mdnsQuery(ip: String, timeoutMs: Int = 1000): String? {
    for (attempt in 1..2) {
        try {
            val parts = ip.split('.').reversed()
            val reverseIp = "${parts[0]}.${parts[1]}.${parts[2]}.${parts[3]}.in-addr.arpa"

            val socket = DatagramSocket()
            socket.soTimeout = timeoutMs * attempt

            val queryId = (System.currentTimeMillis() and 0xFFFF).toInt()
            val query = buildMdnsQuery(queryId, reverseIp)

            val target = InetAddress.getByName("224.0.0.251")
            socket.send(DatagramPacket(query, query.size, target, 5353))

            val buffer = ByteArray(512)
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)

            val hostname = parseMdnsResponse(buffer, response.length)
            socket.close()

            if (hostname != null) {
                return hostname.removeSuffix(".local")?.removeSuffix(".")
            }
        } catch (e: Exception) {
        }
    }

    return try {
        bonjourServiceQuery(ip, timeoutMs)
    } catch (e: Exception) {
        println("$ip Bonjour service discovery failed: ${e.message}")
        null
    }
}

fun bonjourServiceQuery(ip: String, timeoutMs: Int = 1000): String? {
    println("$ip Trying Bonjour service discovery...")
    val services = listOf(
        "_smb._tcp.local.",
        "_afpovertcp._tcp.local.",
        "_ssh._tcp.local.",
        "_device-info._tcp.local.",
        "_workstation._tcp.local.",
        "_airport._tcp.local."
    )

    for (service in services) {
        try {
            val socket = DatagramSocket()
            socket.soTimeout = timeoutMs

            val queryId = (System.currentTimeMillis() and 0xFFFF).toInt()
            val query = buildMdnsServiceQuery(queryId, service)

            val target = InetAddress.getByName("224.0.0.251")
            socket.send(DatagramPacket(query, query.size, target, 5353))

            val endTime = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < endTime) {
                try {
                    val buffer = ByteArray(1024)
                    val response = DatagramPacket(buffer, buffer.size)
                    socket.receive(response)

                    if (response.address.hostAddress == ip) {
                        val hostname = parseBonjourServiceResponse(buffer, response.length)
                        if (hostname != null) {
                            socket.close()
                            println("$ip Bonjour service $service: $hostname")
                            return hostname.removeSuffix(".local")?.removeSuffix(".")
                        }
                    }
                } catch (e: SocketTimeoutException) {
                    break
                }
            }

            socket.close()
        } catch (e: Exception) {
        }
    }

    return null
}

fun buildMdnsQuery(id: Int, name: String): ByteArray {
    val buffer = java.nio.ByteBuffer.allocate(512)

    buffer.putShort(id.toShort())
    buffer.putShort(0x0000.toShort())
    buffer.putShort(0x0001.toShort())
    buffer.putShort(0x0000.toShort())
    buffer.putShort(0x0000.toShort())
    buffer.putShort(0x0000.toShort())

    val labels = name.split('.')
    for (label in labels) {
        buffer.put(label.length.toByte())
        buffer.put(label.toByteArray())
    }
    buffer.put(0x00)

    buffer.putShort(0x000C.toShort())
    buffer.putShort(0x0001.toShort())

    return buffer.array().copyOf(buffer.position())
}

fun buildMdnsServiceQuery(id: Int, serviceName: String): ByteArray {
    val buffer = java.nio.ByteBuffer.allocate(512)

    buffer.putShort(id.toShort())
    buffer.putShort(0x0000.toShort())
    buffer.putShort(0x0001.toShort())
    buffer.putShort(0x0000.toShort())
    buffer.putShort(0x0000.toShort())
    buffer.putShort(0x0000.toShort())

    val labels = serviceName.split('.')
    for (label in labels) {
        if (label.isNotEmpty()) {
            buffer.put(label.length.toByte())
            buffer.put(label.toByteArray())
        }
    }
    buffer.put(0x00)

    buffer.putShort(0x000C.toShort())
    buffer.putShort(0x0001.toShort())

    return buffer.array().copyOf(buffer.position())
}

fun parseMdnsResponse(data: ByteArray, length: Int): String? {
    if (length < 12) return null

    try {
        var pos = 12

        val qdcount = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        for (i in 0 until qdcount) {
            while (pos < length && data[pos].toInt() != 0) {
                val len = data[pos].toInt() and 0xFF
                if (len >= 0xC0) {
                    pos += 2
                    break
                }
                pos += len + 1
            }
            if (pos < length && data[pos].toInt() == 0) pos++
            pos += 4
        }

        val ancount = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)

        for (i in 0 until ancount) {
            if (pos >= length) break

            while (pos < length) {
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

            if (pos + 10 > length) break

            val type = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            pos += 8

            val rdlength = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            pos += 2

            if (type == 12 && pos + rdlength <= length) {
                val hostname = readMdnsName(data, pos)
                if (hostname != null && hostname.endsWith(".local")) {
                    return hostname
                }
            }

            pos += rdlength
        }
    } catch (e: Exception) {
        return null
    }

    return null
}

fun parseBonjourServiceResponse(data: ByteArray, length: Int): String? {
    if (length < 12) return null

    try {
        var pos = 12

        val qdcount = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        for (i in 0 until qdcount) {
            while (pos < length && data[pos].toInt() != 0) {
                val len = data[pos].toInt() and 0xFF
                if (len >= 0xC0) {
                    pos += 2
                    break
                }
                pos += len + 1
            }
            if (pos < length && data[pos].toInt() == 0) pos++
            pos += 4
        }

        val ancount = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)

        for (i in 0 until ancount) {
            if (pos >= length) break

            while (pos < length) {
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

            if (pos + 10 > length) break

            val type = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            pos += 8

            val rdlength = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            pos += 2

            if (type == 12 && pos + rdlength <= length) {
                val instanceName = readMdnsName(data, pos)
                if (instanceName != null) {
                    val hostname = instanceName.split("._").firstOrNull()
                    if (hostname != null && hostname.isNotBlank()) {
                        return hostname
                    }
                }
            }

            pos += rdlength
        }
    } catch (e: Exception) {
        return null
    }

    return null
}

fun readMdnsName(data: ByteArray, startPos: Int): String? {
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

fun netbiosLookup(ip: String, timeoutMs: Int): String? {
    for (attempt in 1..3) {
        try {
            val socket = DatagramSocket()
            socket.soTimeout = if (attempt == 1) timeoutMs else timeoutMs * 2

            val tid = (System.nanoTime().toInt() and 0xFFFF)
            val query = buildNetbiosQuery(tid)
            val target = InetAddress.getByName(ip)

            socket.send(DatagramPacket(query, query.size, target, 137))

            val buf = ByteArray(1024)
            val resp = DatagramPacket(buf, buf.size)
            socket.receive(resp)

            val result = parseNetbiosName(buf, resp.length)
            socket.close()

            if (result != null) {
                return result
            }
        } catch (e: Exception) {
            if (attempt == 3) {
                return null
            }
        }
    }
    return null
}

fun buildNetbiosQuery(tid: Int): ByteArray {
    val bb = java.nio.ByteBuffer.allocate(50)
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

fun encodeNetbiosName(n: String): ByteArray {
    val out = ByteArray(34)
    out[0] = 0x20
    val upper = n.uppercase().padEnd(15, ' ')
    val bytes = upper.toByteArray(java.nio.charset.Charset.forName("US-ASCII"))
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

fun parseNetbiosName(buf: ByteArray, len: Int): String? {
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
        val raw = String(nameBytes, java.nio.charset.Charset.forName("US-ASCII")).trim()
        p += 18
        if (raw.isNotBlank()) return raw
    }
    return null
}

fun portScanWithBanner(ip: String, timeoutMs: Int = 500): PortBannerResult {
    var httpServer: String? = null
    var deviceType: String? = null
    var discoveredName: String? = null
    val openPrinterPorts = mutableListOf<Int>()

    val portsToCheck = listOf(
        22 to "SSH",
        80 to "HTTP",
        443 to "HTTPS",
        445 to "SMB",
        548 to "AFP",
        631 to "IPP",
        5353 to "mDNS",
        8080 to "HTTP-Alt",
        9100 to "Printer"
    )

    for ((port, serviceName) in portsToCheck) {
        try {
            val socket = Socket()
            socket.connect(InetSocketAddress(ip, port), timeoutMs)
            socket.soTimeout = timeoutMs

            when (port) {
                22 -> {
                    val banner = socket.getInputStream().bufferedReader().readLine()
                    socket.close()
                    if (banner != null) {
                        println("$ip SSH banner: $banner")
                        val parts = banner.split(" ")
                        if (parts.size > 1) {
                            val possibleHostname = parts.last()
                            if (possibleHostname.isNotBlank() && !possibleHostname.startsWith("OpenSSH")) {
                                discoveredName = possibleHostname
                            }
                        }
                    }
                }
                80, 8080 -> {
                    val output = socket.getOutputStream()
                    output.write("HEAD / HTTP/1.0\r\nHost: $ip\r\n\r\n".toByteArray())
                    output.flush()

                    val response = socket.getInputStream().bufferedReader().use { it.readText() }
                    socket.close()

                    val serverHeader = response.lines().find { it.startsWith("Server:", ignoreCase = true) }
                    if (serverHeader != null) {
                        val serverValue = serverHeader.substringAfter(":", "").trim()
                        httpServer = serverValue
                        println("$ip HTTP Server: $serverHeader")

                        val lowerServer = serverValue.lowercase()
                        when {
                            "lexmark" in lowerServer || "printer" in lowerServer -> deviceType = "Printer"
                            "hp" in lowerServer && ("jet" in lowerServer || "laserjet" in lowerServer || "officejet" in lowerServer) -> deviceType = "Printer"
                            "brother" in lowerServer -> deviceType = "Printer"
                            "epson" in lowerServer -> deviceType = "Printer"
                            "canon" in lowerServer -> deviceType = "Printer"
                            "xerox" in lowerServer -> deviceType = "Printer"
                        }
                    }
                }
                445 -> {
                    socket.close()
                    println("$ip SMB service available")
                }
                631, 9100 -> {
                    socket.close()
                    openPrinterPorts.add(port)
                    println("$ip Printer port $port open")
                }
            }

            socket.close()
        } catch (e: Exception) {
        }
    }

    if (deviceType == null && openPrinterPorts.size >= 2) {
        println("$ip Detected as printer: ${openPrinterPorts.size} printer ports open: $openPrinterPorts")
        deviceType = "Printer"
    }

    println("$ip PortBannerResult: httpServer=$httpServer, deviceType=$deviceType, name=$discoveredName")
    return PortBannerResult(httpServer, deviceType, discoveredName)
}