# Tesla BLE Garage Opener

## Project Overview

This Android application serves as a bridge between a custom ESP32 BLE (Bluetooth Low Energy) device and a Home Assistant server. The primary goal of this project is to allow a physical button inside a Tesla vehicle to trigger a command that opens a garage door via Home Assistant.

## How It Works

1.  **ESP32 BLE Server**: An ESP32 microcontroller is installed in the Tesla and connected to a physical button. It acts as a BLE server, waiting for a button press.
2.  **Button Press**: When the physical button is pressed, the ESP32 sends a command over BLE.
3.  **Android Application**: This Android application, running on a phone connected to the ESP32, receives the BLE command.
4.  **Home Assistant Integration**: The application then makes a secure API call to a Home Assistant server.
5.  **Garage Door Control**: Home Assistant receives the command and triggers the automation to open or close the garage door.
