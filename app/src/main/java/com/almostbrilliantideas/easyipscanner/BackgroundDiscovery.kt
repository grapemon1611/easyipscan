package com.almostbrilliantideas.easyipscanner

import android.content.Context
import android.net.wifi.WifiManager
import java.net.*
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

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

                    val searchTargets = listOf(
                        "roku:ecp",
                        "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
                        "ssdp:all"
                    )

                    for (st in searchTargets) {
                        val ssdpRequest = """
                            M-SEARCH * HTTP/1.1
                            HOST: 239.255.255.250:1900
                            MAN: "ssdp:discover"
                            MX: 3
                            ST: $st
                            
                            
                        """.trimIndent().replace("\n", "\r\n")

                        val searchPacket = DatagramPacket(
                            ssdpRequest.toByteArray(),
                            ssdpRequest.length,
                            InetAddress.getByName("239.255.255.250"),
                            1900
                        )

                        println("SSDP: Sending M-SEARCH with ST: $st")
                        socket.send(searchPacket)

                        Thread.sleep(100)
                    }

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

                Thread {
                    try {
                        val url = URL(locationUrl)
                        val connection = url.openConnection() as HttpURLConnection
                        connection.connectTimeout = 3000
                        connection.readTimeout = 3000

                        if (connection.responseCode == 200) {
                            val xml = connection.inputStream.bufferedReader().use { it.readText() }
                            parseDeviceDescription(xml, sourceIp)
                        }
                        connection.disconnect()

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

    private fun parseRokuDeviceInfo(xml: String, sourceIp: String) {
        try {
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
                        "_http._tcp.local.",
                        "_https._tcp.local.",
                        "_ssh._tcp.local.",
                        "_printer._tcp.local.",
                        "_ipp._tcp.local.",
                        "_airplay._tcp.local.",
                        "_raop._tcp.local.",
                        "_spotify-connect._tcp.local.",
                        "_device-info._tcp.local.",
                        "_companion-link._tcp.local.",
                        "_rdlink._tcp.local.",
                        "_apple-mobdev._tcp.local.",
                        "_afpovertcp._tcp.local.",
                        "_smb._tcp.local.",
                        "_airport._tcp.local."
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