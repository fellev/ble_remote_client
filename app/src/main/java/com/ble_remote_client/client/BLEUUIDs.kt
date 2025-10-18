package com.ble_remote_client.client

import java.util.UUID

object BLEUUIDs {
    const val SERVICE_UUID_STRING = "0000ff00-0000-1000-8000-00805f9b34fb"
    val SERVICE_UUID: UUID = UUID.fromString(SERVICE_UUID_STRING)

    const val CHAR_UUID_STRING = "00002902-0000-1000-8000-00805f9b34fb"
    val CHAR_UUID: UUID = UUID.fromString(CHAR_UUID_STRING)
}