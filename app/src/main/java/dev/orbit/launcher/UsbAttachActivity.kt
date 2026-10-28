package dev.orbit.launcher

import android.app.Activity
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import dev.orbit.launcher.ui.MainActivity

/**
 * Система відкриває цю невидиму activity, коли підключається USB-пристрій з device_filter.xml.
 * Тут вирішуємо, чи це наш геймпад, і якщо так — відкриваємо Orbit.
 */
class UsbAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as OrbitApp
        val prefs = app.prefs
        @Suppress("DEPRECATION")
        val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

        val recentlyLaunchedGame = System.currentTimeMillis() - prefs.inGameSince < 3 * 60 * 60 * 1000L
        if (device != null && prefs.autostart && !recentlyLaunchedGame && matches(device, prefs.boundController)) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .putExtra(MainActivity.EXTRA_FROM_CONTROLLER, true),
            )
        }
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        fun id(d: UsbDevice) = "%04x:%04x".format(d.vendorId, d.productId)

        fun looksLikeGamepad(d: UsbDevice): Boolean {
            if (d.vendorId == 0x3537) return true // GameSir
            for (i in 0 until d.interfaceCount) {
                val itf = d.getInterface(i)
                // XInput (Xbox 360/One-сумісні)
                if (itf.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC && itf.interfaceSubclass == 93) return true
                // HID без протоколу клавіатури(1)/миші(2) — найімовірніше геймпад
                if (itf.interfaceClass == UsbConstants.USB_CLASS_HID && itf.interfaceProtocol == 0) return true
            }
            return false
        }

        fun matches(d: UsbDevice, bound: String) =
            if (bound.isNotBlank()) id(d) == bound else looksLikeGamepad(d)
    }
}
