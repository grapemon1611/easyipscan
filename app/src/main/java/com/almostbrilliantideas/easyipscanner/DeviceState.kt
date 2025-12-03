package com.almostbrilliantideas.easyipscanner

// Device state categories for UI display
enum class DeviceState {
    NEW,           // Just discovered (first_seen == last_seen)
    BACK_ONLINE,   // Was offline, now online
    STILL_ONLINE,  // Was online, still online
    WENT_OFFLINE,  // Was online, now offline
    HISTORICAL     // Offline (for devices not seen recently)
}

// Categorize devices by their state for display
fun categorizeDevices(
    devices: List<StoredDevice>,
    scanStartTime: Long,
    historicalCutoffDays: Int = 7
): Map<DeviceState, List<StoredDevice>> {
    val historicalCutoff = scanStartTime - (historicalCutoffDays * 24 * 60 * 60 * 1000L)

    val categorized = mutableMapOf<DeviceState, MutableList<StoredDevice>>()
    DeviceState.values().forEach { categorized[it] = mutableListOf() }

    println("=== CATEGORIZING ${devices.size} DEVICES ===")

    for (device in devices) {
        val state = when {
            device.status == "online" && device.firstSeen == device.lastSeen -> {
                println("${device.ip}: NEW (status=${device.status}, first=${device.firstSeen}, last=${device.lastSeen})")
                DeviceState.NEW
            }

            device.status == "online" && device.firstSeen < device.lastSeen -> {
                println("${device.ip}: STILL_ONLINE (status=${device.status}, first=${device.firstSeen}, last=${device.lastSeen})")
                DeviceState.STILL_ONLINE
            }

            device.status == "offline" && device.lastSeen >= historicalCutoff -> {
                println("${device.ip}: WENT_OFFLINE (status=${device.status}, last=${device.lastSeen}, cutoff=${historicalCutoff})")
                DeviceState.WENT_OFFLINE
            }

            device.status == "offline" && device.lastSeen < historicalCutoff -> {
                println("${device.ip}: HISTORICAL (status=${device.status}, last=${device.lastSeen}, cutoff=${historicalCutoff})")
                DeviceState.HISTORICAL
            }

            device.status == "online" -> {
                println("${device.ip}: STILL_ONLINE (default, status=${device.status})")
                DeviceState.STILL_ONLINE
            }

            else -> {
                println("${device.ip}: HISTORICAL (fallback, status=${device.status})")
                DeviceState.HISTORICAL
            }
        }

        categorized[state]?.add(device)
    }

    println("=== CATEGORIZATION COMPLETE ===")
    categorized.forEach { (state, devs) ->
        println("  $state: ${devs.size} devices")
    }

    return categorized
}