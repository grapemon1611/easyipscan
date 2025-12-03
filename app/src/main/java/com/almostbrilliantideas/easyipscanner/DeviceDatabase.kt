package com.almostbrilliantideas.easyipscanner

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class StoredDevice(
    val ip: String,
    val displayName: String?,
    val customName: String?,  // User-provided custom name (overrides discovered name)
    val ssdpName: String?,
    val mdnsName: String?,
    val netbiosName: String?,
    val dnsName: String?,
    val vendor: String?,
    val firstSeen: Long,
    val lastSeen: Long,
    val status: String  // "online" or "offline"
)

class DeviceDatabase(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    companion object {
        private const val DATABASE_NAME = "easyip_scanner.db"
        private const val DATABASE_VERSION = 2  // Incremented for custom_name column
        private const val TABLE_DEVICES = "devices"

        // Column names
        private const val COL_IP = "ip"
        private const val COL_DISPLAY_NAME = "display_name"
        private const val COL_CUSTOM_NAME = "custom_name"
        private const val COL_SSDP_NAME = "ssdp_name"
        private const val COL_MDNS_NAME = "mdns_name"
        private const val COL_NETBIOS_NAME = "netbios_name"
        private const val COL_DNS_NAME = "dns_name"
        private const val COL_VENDOR = "vendor"
        private const val COL_FIRST_SEEN = "first_seen"
        private const val COL_LAST_SEEN = "last_seen"
        private const val COL_STATUS = "status"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTable = """
            CREATE TABLE $TABLE_DEVICES (
                $COL_IP TEXT PRIMARY KEY,
                $COL_DISPLAY_NAME TEXT,
                $COL_CUSTOM_NAME TEXT,
                $COL_SSDP_NAME TEXT,
                $COL_MDNS_NAME TEXT,
                $COL_NETBIOS_NAME TEXT,
                $COL_DNS_NAME TEXT,
                $COL_VENDOR TEXT,
                $COL_FIRST_SEEN INTEGER NOT NULL,
                $COL_LAST_SEEN INTEGER NOT NULL,
                $COL_STATUS TEXT NOT NULL
            )
        """.trimIndent()

        db.execSQL(createTable)
        println("DeviceDatabase: Table created successfully")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Add custom_name column for existing databases
            db.execSQL("ALTER TABLE $TABLE_DEVICES ADD COLUMN $COL_CUSTOM_NAME TEXT")
            println("DeviceDatabase: Added custom_name column")
        }
    }

    /**
     * Insert or update a device in the database.
     * If the device exists and vendor matches (or either is null), update it.
     * If vendor doesn't match, treat as new device (clear old data).
     * IMPORTANT: This preserves custom_name - user tags are never overwritten by scan results.
     */
    fun upsertDevice(
        ip: String,
        names: DeviceNames,
        vendor: String?,
        currentTime: Long
    ): Boolean {
        val db = writableDatabase

        return try {
            // Check if device exists
            val cursor = db.query(
                TABLE_DEVICES,
                arrayOf(COL_VENDOR, COL_FIRST_SEEN, COL_CUSTOM_NAME, COL_SSDP_NAME, COL_MDNS_NAME, COL_NETBIOS_NAME, COL_DNS_NAME, COL_DISPLAY_NAME),
                "$COL_IP = ?",
                arrayOf(ip),
                null, null, null
            )

            val shouldReset = if (cursor.moveToFirst()) {
                val storedVendor = cursor.getString(cursor.getColumnIndexOrThrow(COL_VENDOR))

                // Vendor mismatch detection: if both are non-null and different, reset
                val vendorMismatch = storedVendor != null && vendor != null && storedVendor != vendor

                if (vendorMismatch) {
                    println("DeviceDB: Vendor mismatch at $ip (was '$storedVendor', now '$vendor') - resetting device")
                }

                vendorMismatch
            } else {
                false
            }

            val firstSeen = if (cursor.moveToFirst() && !shouldReset) {
                cursor.getLong(cursor.getColumnIndexOrThrow(COL_FIRST_SEEN))
            } else {
                currentTime
            }

            // Preserve custom name - NEVER overwrite user tags
            val customName = if (cursor.moveToFirst() && !shouldReset) {
                cursor.getString(cursor.getColumnIndexOrThrow(COL_CUSTOM_NAME))
            } else {
                null
            }

            // Get previously stored names to apply persistence logic
            val storedNames = if (cursor.moveToFirst() && !shouldReset) {
                DeviceNames(
                    ssdp = cursor.getString(cursor.getColumnIndexOrThrow(COL_SSDP_NAME)),
                    mdns = cursor.getString(cursor.getColumnIndexOrThrow(COL_MDNS_NAME)),
                    netbios = cursor.getString(cursor.getColumnIndexOrThrow(COL_NETBIOS_NAME)),
                    dns = cursor.getString(cursor.getColumnIndexOrThrow(COL_DNS_NAME))
                )
            } else {
                null
            }

            cursor.close()

            // Apply name persistence: higher-priority names "stick" when vendor matches
            val finalNames = if (storedNames != null && !shouldReset) {
                DeviceNames(
                    ssdp = names.ssdp ?: storedNames.ssdp,  // Keep SSDP if we had it
                    rokuHttp = names.rokuHttp,
                    mdns = names.mdns ?: storedNames.mdns,
                    netbios = names.netbios ?: storedNames.netbios,
                    dns = names.dns ?: storedNames.dns,
                    httpServer = names.httpServer,
                    deviceType = names.deviceType
                )
            } else {
                names
            }

            // Display name priority: custom_name > discovered name > "Unknown Device"
            val displayName = customName ?: finalNames.getBestName()

            val values = ContentValues().apply {
                put(COL_IP, ip)
                put(COL_DISPLAY_NAME, displayName)
                put(COL_CUSTOM_NAME, customName)  // Preserve existing custom name
                put(COL_SSDP_NAME, finalNames.ssdp)
                put(COL_MDNS_NAME, finalNames.mdns)
                put(COL_NETBIOS_NAME, finalNames.netbios)
                put(COL_DNS_NAME, finalNames.dns)
                put(COL_VENDOR, vendor)
                put(COL_FIRST_SEEN, firstSeen)
                put(COL_LAST_SEEN, currentTime)
                put(COL_STATUS, "online")
            }

            val result = db.insertWithOnConflict(
                TABLE_DEVICES,
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE
            )

            result != -1L
        } catch (e: Exception) {
            println("DeviceDB: Error upserting device $ip: ${e.message}")
            false
        }
    }

    /**
     * Update custom name for a device (user tagging)
     */
    fun setCustomName(ip: String, customName: String?): Boolean {
        val db = writableDatabase

        return try {
            // First, get the current device to recalculate display_name
            val device = getDevice(ip) ?: return false

            // Display name priority: custom_name > discovered name > "Unknown Device"
            val newDisplayName = if (customName != null && customName.isNotBlank()) {
                customName
            } else {
                // Fall back to discovered names
                DeviceNames(
                    ssdp = device.ssdpName,
                    mdns = device.mdnsName,
                    netbios = device.netbiosName,
                    dns = device.dnsName
                ).getBestName()
            }

            val values = ContentValues().apply {
                put(COL_CUSTOM_NAME, customName)
                put(COL_DISPLAY_NAME, newDisplayName)
            }

            val result = db.update(
                TABLE_DEVICES,
                values,
                "$COL_IP = ?",
                arrayOf(ip)
            )

            result > 0
        } catch (e: Exception) {
            println("DeviceDB: Error setting custom name for $ip: ${e.message}")
            false
        }
    }

    /**
     * Mark all devices not seen in this scan as offline
     */
    fun markOfflineDevicesNotSeenSince(scanTime: Long): Int {
        val db = writableDatabase

        return try {
            val values = ContentValues().apply {
                put(COL_STATUS, "offline")
            }

            db.update(
                TABLE_DEVICES,
                values,
                "$COL_LAST_SEEN < ?",
                arrayOf(scanTime.toString())
            )
        } catch (e: Exception) {
            println("DeviceDB: Error marking offline devices: ${e.message}")
            0
        }
    }

    /**
     * Get all devices from database
     */
    fun getAllDevices(): List<StoredDevice> {
        val devices = mutableListOf<StoredDevice>()
        val db = readableDatabase

        try {
            val cursor = db.query(
                TABLE_DEVICES,
                null,
                null,
                null,
                null,
                null,
                "$COL_LAST_SEEN DESC"
            )

            while (cursor.moveToNext()) {
                devices.add(
                    StoredDevice(
                        ip = cursor.getString(cursor.getColumnIndexOrThrow(COL_IP)),
                        displayName = cursor.getString(cursor.getColumnIndexOrThrow(COL_DISPLAY_NAME)),
                        customName = cursor.getString(cursor.getColumnIndexOrThrow(COL_CUSTOM_NAME)),
                        ssdpName = cursor.getString(cursor.getColumnIndexOrThrow(COL_SSDP_NAME)),
                        mdnsName = cursor.getString(cursor.getColumnIndexOrThrow(COL_MDNS_NAME)),
                        netbiosName = cursor.getString(cursor.getColumnIndexOrThrow(COL_NETBIOS_NAME)),
                        dnsName = cursor.getString(cursor.getColumnIndexOrThrow(COL_DNS_NAME)),
                        vendor = cursor.getString(cursor.getColumnIndexOrThrow(COL_VENDOR)),
                        firstSeen = cursor.getLong(cursor.getColumnIndexOrThrow(COL_FIRST_SEEN)),
                        lastSeen = cursor.getLong(cursor.getColumnIndexOrThrow(COL_LAST_SEEN)),
                        status = cursor.getString(cursor.getColumnIndexOrThrow(COL_STATUS))
                    )
                )
            }

            cursor.close()
        } catch (e: Exception) {
            println("DeviceDB: Error getting all devices: ${e.message}")
        }

        return devices
    }

    /**
     * Get device by IP
     */
    fun getDevice(ip: String): StoredDevice? {
        val db = readableDatabase

        try {
            val cursor = db.query(
                TABLE_DEVICES,
                null,
                "$COL_IP = ?",
                arrayOf(ip),
                null,
                null,
                null
            )

            if (cursor.moveToFirst()) {
                val device = StoredDevice(
                    ip = cursor.getString(cursor.getColumnIndexOrThrow(COL_IP)),
                    displayName = cursor.getString(cursor.getColumnIndexOrThrow(COL_DISPLAY_NAME)),
                    customName = cursor.getString(cursor.getColumnIndexOrThrow(COL_CUSTOM_NAME)),
                    ssdpName = cursor.getString(cursor.getColumnIndexOrThrow(COL_SSDP_NAME)),
                    mdnsName = cursor.getString(cursor.getColumnIndexOrThrow(COL_MDNS_NAME)),
                    netbiosName = cursor.getString(cursor.getColumnIndexOrThrow(COL_NETBIOS_NAME)),
                    dnsName = cursor.getString(cursor.getColumnIndexOrThrow(COL_DNS_NAME)),
                    vendor = cursor.getString(cursor.getColumnIndexOrThrow(COL_VENDOR)),
                    firstSeen = cursor.getLong(cursor.getColumnIndexOrThrow(COL_FIRST_SEEN)),
                    lastSeen = cursor.getLong(cursor.getColumnIndexOrThrow(COL_LAST_SEEN)),
                    status = cursor.getString(cursor.getColumnIndexOrThrow(COL_STATUS))
                )
                cursor.close()
                return device
            }

            cursor.close()
        } catch (e: Exception) {
            println("DeviceDB: Error getting device $ip: ${e.message}")
        }

        return null
    }

    /**
     * Delete a device by IP
     */
    fun deleteDevice(ip: String): Boolean {
        val db = writableDatabase

        return try {
            val result = db.delete(
                TABLE_DEVICES,
                "$COL_IP = ?",
                arrayOf(ip)
            )
            result > 0
        } catch (e: Exception) {
            println("DeviceDB: Error deleting device $ip: ${e.message}")
            false
        }
    }

    /**
     * Clear all devices from database (for testing)
     */
    fun clearAllDevices(): Boolean {
        val db = writableDatabase

        return try {
            db.delete(TABLE_DEVICES, null, null)
            println("DeviceDB: All devices cleared")
            true
        } catch (e: Exception) {
            println("DeviceDB: Error clearing devices: ${e.message}")
            false
        }
    }

    /**
     * Get device count
     */
    fun getDeviceCount(): Int {
        val db = readableDatabase

        return try {
            val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_DEVICES", null)
            cursor.moveToFirst()
            val count = cursor.getInt(0)
            cursor.close()
            count
        } catch (e: Exception) {
            println("DeviceDB: Error getting device count: ${e.message}")
            0
        }
    }
}