package com.retrostick.launcher

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.StatFs
import android.view.InputDevice
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Which button glyphs to show: follows the controller family (USB/BT vendor id). */
enum class PadStyle { PLAYSTATION, NINTENDO, XBOX, REMOTE }

private const val VENDOR_SONY = 0x054C
private const val VENDOR_NINTENDO = 0x057E
private const val PRODUCT_DUALSENSE = 0x0CE6
private const val PRODUCT_PRO_CONTROLLER = 0x2009

/**
 * Android < 11 has no key layout for the DualSense, so Generic.kl maps its HID buttons in HID order.
 * Measured on the stick with getevent (docs/mandos.md): received code -> the button actually pressed.
 */
private val DUALSENSE_GENERIC_KL = mapOf(
    KeyEvent.KEYCODE_BUTTON_B to KeyEvent.KEYCODE_BUTTON_A,           // Cross
    KeyEvent.KEYCODE_BUTTON_C to KeyEvent.KEYCODE_BUTTON_B,           // Circle
    KeyEvent.KEYCODE_BUTTON_A to KeyEvent.KEYCODE_BUTTON_X,           // Square
    KeyEvent.KEYCODE_BUTTON_X to KeyEvent.KEYCODE_BUTTON_Y,           // Triangle
    KeyEvent.KEYCODE_BUTTON_Y to KeyEvent.KEYCODE_BUTTON_L1,
    KeyEvent.KEYCODE_BUTTON_Z to KeyEvent.KEYCODE_BUTTON_R1,
    KeyEvent.KEYCODE_BUTTON_L1 to KeyEvent.KEYCODE_BUTTON_L2,
    KeyEvent.KEYCODE_BUTTON_R1 to KeyEvent.KEYCODE_BUTTON_R2,
    KeyEvent.KEYCODE_BUTTON_L2 to KeyEvent.KEYCODE_BUTTON_SELECT,     // Create
    KeyEvent.KEYCODE_BUTTON_R2 to KeyEvent.KEYCODE_BUTTON_START,      // Options
    KeyEvent.KEYCODE_BUTTON_SELECT to KeyEvent.KEYCODE_BUTTON_THUMBL, // L3
    KeyEvent.KEYCODE_BUTTON_START to KeyEvent.KEYCODE_BUTTON_THUMBR,  // R3
)

/**
 * The Switch Pro Controller on Android 9 also comes through Generic.kl in HID order (getevent, 2026-11-09):
 * A=BUTTON_A, B=BUTTON_B, X=BUTTON_C, Y=BUTTON_X, then the same codes as the DualSense. Played like the
 * PlayStation pad (user's choice): A confirms (as Cross), B goes back (Circle), Y = Square (Detalles),
 * X = Triangle (Favorito); L/R = L1/R1, ZL/ZR = L2/R2, - = Create, + = Options.
 */
private val PRO_CONTROLLER_GENERIC_KL = DUALSENSE_GENERIC_KL + mapOf(
    KeyEvent.KEYCODE_BUTTON_A to KeyEvent.KEYCODE_BUTTON_A,           // A: confirm
    KeyEvent.KEYCODE_BUTTON_B to KeyEvent.KEYCODE_BUTTON_B,           // B: back
    KeyEvent.KEYCODE_BUTTON_C to KeyEvent.KEYCODE_BUTTON_Y,           // X: Triangle
    KeyEvent.KEYCODE_BUTTON_X to KeyEvent.KEYCODE_BUTTON_X,           // Y: Square
)

object StickStatus {

    /**
     * The stick's remote is not a gamepad, and the system "virtual-search" device claims SOURCE_GAMEPAD
     * but has no USB/BT vendor id.
     */
    fun isGamepad(device: InputDevice?) = device != null && !device.isVirtual && device.vendorId != 0 &&
        (device.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD

    fun gamepads(): List<InputDevice> =
        InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }.filter(::isGamepad)

    /**
     * A controller's battery, 0-100, or null when the stick can't tell. Asked to Android's Bluetooth stack
     * (BluetoothDevice.getBatteryLevel, hidden on Android 9): the Mi TV Stick's kernel 4.9 has no driver that reads
     * the DualSense's or the Pro Controller's own battery, so this is mostly null there. Only for a name that matches
     * one paired device (three DualSense look the same, so none of them is guessed).
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun batteryLevel(device: InputDevice): Int? = runCatching {
        val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter() ?: return null
        val bonded = adapter.bondedDevices.filter { it.name == device.name }
        if (bonded.size != 1) return null
        val level = android.bluetooth.BluetoothDevice::class.java.getMethod("getBatteryLevel").invoke(bonded[0]) as Int
        level.takeIf { it in 0..100 }
    }.getOrNull()

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

    /**
     * The key as the app means it — BUTTON_A confirms, BUTTON_B goes back, BUTTON_X = Detalles (left / Square),
     * BUTTON_Y = Favorito (top / Triangle) — whatever the pad: fixes the DualSense and the Pro Controller on
     * Android 9, and swaps A/B on other Nintendo pads (positional codes; Nintendo confirms with A, on the right).
     */
    fun standardKey(device: InputDevice?, code: Int): Int {
        if (device == null) return code
        val oldAndroid = Build.VERSION.SDK_INT < Build.VERSION_CODES.R
        return when {
            oldAndroid && device.vendorId == VENDOR_SONY && device.productId == PRODUCT_DUALSENSE -> DUALSENSE_GENERIC_KL[code] ?: code
            oldAndroid && device.vendorId == VENDOR_NINTENDO && device.productId == PRODUCT_PRO_CONTROLLER -> PRO_CONTROLLER_GENERIC_KL[code] ?: code
            device.vendorId == VENDOR_NINTENDO -> when (code) {
                KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_BUTTON_B
                KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BUTTON_A
                else -> code
            }
            else -> code
        }
    }

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

    /** README 8.3: Partidas/.stick/status.json, uploaded by DriveSync and read by the PWA. */
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
