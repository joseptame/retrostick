package com.retrostick.launcher

import android.os.Build
import java.io.File

/**
 * RetroArch's controller profiles for sticks on Android 11 or later (Mi TV Stick 4K…). The configuration pack and
 * install.ps1 bring the Android 9 ones (the «(Android 9, RetroStick)» files in stick/retroarch/autoconfig/android), whose codes
 * only hold on Android 9: there the pads go through Generic.kl. Android 11 has a key layout of its own for them, so
 * the same buttons arrive with the standard codes. Measured on the Mi TV Stick 4K with `getevent -lt` (2026-11-13):
 *
 *   Pro Controller  A=97 B=96 X=100 Y=99 L=102 R=103 ZL=104 ZR=105 −=109 +=108 L3=106 R3=107 Capture=110
 *                   (Home is Android's own Home: it can't be used in RetroArch)
 *
 * Before each game ([apply]): the Android 11 profile is written, the Android 9 ones are moved to ../disabled and the
 * frame toggle (106, the touchpad / Capture on Android 9) is turned off, as 106 is L3 here. The shortcuts in
 * retroarch.cfg follow [hotkeys]. Nothing changes on Android 9.
 */
object PadProfiles {
    private const val DIR = "$SDCARD/RetroArch/autoconfig/android"
    private const val DISABLED = "$SDCARD/RetroArch/autoconfig/disabled"

    /** Android 11 or later: standard codes for the Pro Controller and the DualSense. */
    val standardCodes get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** RetroArch shortcuts, hold Create / − plus L1 = quick save, R1 = load it, R2 = fast-forward (docs/mandos.md). */
    fun hotkeys(): Map<String, String> = if (standardCodes) mapOf(
        "input_enable_hotkey_btn" to "109",
        "input_save_state_btn" to "102",
        "input_load_state_btn" to "103",
        "input_toggle_fast_forward_btn" to "105",
    ) else mapOf(
        // DualSense / Pro Controller on Android 9 (Generic.kl): Create 104, L1 100, R1 101, R2 103
        "input_enable_hotkey_btn" to "104",
        "input_save_state_btn" to "100",
        "input_load_state_btn" to "101",
        "input_toggle_fast_forward_btn" to "103",
    )

    fun apply() {
        if (!standardCodes) return
        runCatching {
            val dir = File(DIR)
            if (!dir.isDirectory) return
            val pro = File(dir, "Nintendo Switch Pro Controller (Android 11, RetroStick).cfg")
            if (!pro.isFile || pro.readText() != PRO_CONTROLLER) pro.writeText(PRO_CONTROLLER)
            // The Android 9 ones would win on vendor / product id with other names
            dir.listFiles { f -> f.name.endsWith("(Android 9, RetroStick).cfg") }?.forEach { f ->
                File(DISABLED).mkdirs()
                f.renameTo(File(DISABLED, f.name))
            }
            // The DualSense: RetroArch's own profile for new Androids (standard codes), which the pack had set aside
            val dualSense = File(DISABLED, "DualSense Wireless Controller (Android 13).cfg")
            if (dualSense.isFile && dir.listFiles { f -> f.name.startsWith("DualSense") }.isNullOrEmpty()) dualSense.renameTo(File(dir, dualSense.name))
            // The frame toggle was the touchpad / Capture (106) on Android 9; here 106 is L3
            File("$SDCARD/RetroArch/config").listFiles()?.filter { it.isDirectory }?.forEach { core ->
                core.listFiles { f -> f.name.endsWith(".cfg") }?.forEach { cfg ->
                    if (cfg.readText().contains("input_overlay_next_btn = \"106\"")) writeRetroArchCfg(mapOf("input_overlay_next_btn" to "nul"), cfg)
                }
            }
        }
    }

    /** Same as stick/retroarch/autoconfig/android/Nintendo Switch Pro Controller (Android 11, RetroStick).cfg */
    private val PRO_CONTROLLER = """
        # Nintendo Switch Pro Controller on Android 11+ (Mi TV Stick 4K), Bluetooth. Written by the launcher
        # (PadProfiles.kt). Android's own key layout gives the standard codes, measured with getevent -lt (2026-11-13):
        #   A=97 B=96 X=100 Y=99 L=102 R=103 ZL=104 ZR=105 -=109 +=108 L3=106 R3=107 Capture=110 (Home = Android's)
        #   D-pad: DPAD_UP 19, DOWN 20, LEFT 21, RIGHT 22 (keys, not a hat as on Android 9)
        # Played like the PlayStation pad, as on Android 9: A = Cross, B = Circle, Y = Square, X = Triangle.
        input_driver = "android"
        input_device = "Nintendo Switch Pro Controller"
        input_device_display_name = "Pro Controller (Android 11, RetroStick)"
        input_vendor_id = "1406"
        input_product_id = "8201"

        input_b_btn = "97"
        input_a_btn = "96"
        input_y_btn = "99"
        input_x_btn = "100"
        input_l_btn = "102"
        input_r_btn = "103"
        input_l2_btn = "104"
        input_r2_btn = "105"
        input_select_btn = "109"
        input_start_btn = "108"
        input_l3_btn = "106"
        input_r3_btn = "107"
        input_up_btn = "19"
        input_down_btn = "20"
        input_left_btn = "21"
        input_right_btn = "22"
        input_l_x_plus_axis = "+0"
        input_l_x_minus_axis = "-0"
        input_l_y_plus_axis = "+1"
        input_l_y_minus_axis = "-1"
        input_r_x_plus_axis = "+2"
        input_r_x_minus_axis = "-2"
        input_r_y_plus_axis = "+3"
        input_r_y_minus_axis = "-3"

        # Capture opens the RetroArch menu (Home leaves to Android's home screen)
        input_menu_toggle_btn = "110"

        input_b_btn_label = "A"
        input_a_btn_label = "B"
        input_y_btn_label = "Y"
        input_x_btn_label = "X"
        input_l_btn_label = "L"
        input_r_btn_label = "R"
        input_l2_btn_label = "ZL"
        input_r2_btn_label = "ZR"
        input_select_btn_label = "Minus"
        input_start_btn_label = "Plus"
        input_l3_btn_label = "L3"
        input_r3_btn_label = "R3"
        input_menu_toggle_btn_label = "Capture"
    """.trimIndent() + "\n"
}
