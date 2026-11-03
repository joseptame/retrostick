package com.retrostick.launcher

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import android.view.InputDevice
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// ⚠️ Package names to confirm when FolderSync is installed (phase 3).
private val FOLDERSYNC_PACKAGES = listOf("dk.tacit.android.foldersync.full", "dk.tacit.android.foldersync.lite")

/** Which button glyphs to show: follows the controller family (USB/BT vendor id). */
enum class PadStyle { PLAYSTATION, NINTENDO, XBOX, REMOTE }

private const val VENDOR_SONY = 0x054C
private const val VENDOR_NINTENDO = 0x057E

object StickStatus {

    /**
     * The stick's remote is not a gamepad, and the system "virtual-search" device claims SOURCE_GAMEPAD
     * but has no USB/BT vendor id.
     */
    fun isGamepad(device: InputDevice?) = device != null && !device.isVirtual && device.vendorId != 0 &&
        (device.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD

    fun gamepads(): List<InputDevice> =
        InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }.filter(::isGamepad)

    /** Names of connected game controllers, in player order. */
    fun controllers(): List<String> = gamepads().map { it.name }

    /** Unknown brands (8BitDo, generic pads...) follow Android's own A/B/X/Y naming, i.e. the Xbox layout. */
    fun padStyle(device: InputDevice?): PadStyle = when {
        !isGamepad(device) -> PadStyle.REMOTE
        device!!.vendorId == VENDOR_SONY || device.name.startsWith("DualSense") -> PadStyle.PLAYSTATION
        device.vendorId == VENDOR_NINTENDO || device.name.contains("Pro Controller") -> PadStyle.NINTENDO
        else -> PadStyle.XBOX
    }

    fun firstPadStyle() = gamepads().firstOrNull()?.let(::padStyle) ?: PadStyle.REMOTE

    fun shortControllerName(name: String) = when {
        name.startsWith("DualSense") -> "DualSense"
        name == "Wireless Controller" -> "Mando PlayStation"
        name.contains("Xbox", ignoreCase = true) -> "Mando Xbox"
        else -> name
    }

    /** "Wi-Fi", "Ethernet" or null when offline. */
    fun network(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return null) ?: return null
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Red"
        }
    }

    fun freeBytes() = StatFs(SDCARD).availableBytes

    fun romsBytes() = File("$SDCARD/ROMs").walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun folderSyncLaunchIntent(context: Context): Intent? =
        FOLDERSYNC_PACKAGES.firstNotNullOfOrNull { context.packageManager.getLaunchIntentForPackage(it) }

    /** README 8.3: Partidas/.stick/status.json, uploaded by FolderSync and read by the PWA. */
    fun writeStatusJson(context: Context) {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val json = JSONObject()
            .put("updatedAt", iso.format(Date()))
            .put("freeBytes", freeBytes())
            .put("romsBytes", romsBytes())
            .put("controllers", JSONArray(controllers()))
            .put("appVersion", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
        val target = File("$SDCARD/Partidas/.stick/status.json")
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".tmp")
        tmp.writeText(json.toString(2))
        tmp.renameTo(target)
    }
}
