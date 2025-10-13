package com.ble_remote_client.client

import java.util.UUID

object BLEUUIDs {
    const val SERVICE_UUID_STRING = "000000ff-0000-1000-8000-00805f9b34fb"
    val SERVICE_UUID: UUID = UUID.fromString(SERVICE_UUID_STRING)
    val CHAR_UUID: UUID = UUID.fromString("00001234-0000-1000-8000-00805f9b34fb")
}