package com.ble_remote_client

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

data class AppConfig(
    var haToken: String = "",
    var buttonConfigs: MutableList<ButtonConfig> = MutableList(4) { ButtonConfig(name = "Button ${it + 1}") }
)