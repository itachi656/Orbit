package dev.orbit.launcher.input

import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

enum class Btn { Up, Down, Left, Right, A, B, X, Y, L1, R1, L2, R2, Start, Select, Guide, L3, R3 }

/**
 * Власна обробка геймпада замість фокус-системи Compose: передбачувано, без «загублених» фокусів.
 * D-pad, хрестовина як HAT-вісь та лівий стік — усе зводиться до Up/Down/Left/Right з автоповтором.
 */
class Gamepad(private val onButton: (Btn) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private var heldX: Btn? = null
    private var heldY: Btn? = null

    fun onKey(event: KeyEvent): Boolean {
        val btn = map(event.keyCode) ?: return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            val isDir = btn in setOf(Btn.Up, Btn.Down, Btn.Left, Btn.Right)
            if (event.repeatCount == 0 || isDir) onButton(btn)
        }
        return true
    }

    fun onMotion(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK &&
            event.source and InputDevice.SOURCE_GAMEPAD != InputDevice.SOURCE_GAMEPAD
        ) return false
        if (event.action != MotionEvent.ACTION_MOVE) return false

        val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val sx = event.getAxisValue(MotionEvent.AXIS_X)
        val sy = event.getAxisValue(MotionEvent.AXIS_Y)
        val x = if (abs(hx) > 0.5f) hx else sx
        val y = if (abs(hy) > 0.5f) hy else sy

        setHeld(axis = 0, if (x < -0.55f) Btn.Left else if (x > 0.55f) Btn.Right else null)
        setHeld(axis = 1, if (y < -0.55f) Btn.Up else if (y > 0.55f) Btn.Down else null)

        val l2 = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
        val r2 = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))
        trigger(Btn.L2, l2)
        trigger(Btn.R2, r2)
        return true
    }

    private val triggerDown = mutableSetOf<Btn>()
    private fun trigger(b: Btn, v: Float) {
        if (v > 0.6f && triggerDown.add(b)) onButton(b)
        if (v < 0.3f) triggerDown.remove(b)
    }

    private fun setHeld(axis: Int, btn: Btn?) {
        val current = if (axis == 0) heldX else heldY
        if (btn == current) return
        val r = if (axis == 0) repeatX else repeatY
        handler.removeCallbacks(r)
        if (axis == 0) heldX = btn else heldY = btn
        if (btn != null) {
            onButton(btn)
            handler.postDelayed(r, 380)
        }
    }

    private val repeatX: Runnable = object : Runnable {
        override fun run() { heldX?.let { onButton(it); handler.postDelayed(this, 95) } }
    }
    private val repeatY: Runnable = object : Runnable {
        override fun run() { heldY?.let { onButton(it); handler.postDelayed(this, 120) } }
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        heldX = null; heldY = null
    }

    private fun map(code: Int): Btn? = when (code) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W -> Btn.Up
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S -> Btn.Down
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A -> Btn.Left
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D -> Btn.Right
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE -> Btn.A
        KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> Btn.B
        KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_X -> Btn.X
        KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_Y -> Btn.Y
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_Q -> Btn.L1
        KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_E -> Btn.R1
        KeyEvent.KEYCODE_BUTTON_L2 -> Btn.L2
        KeyEvent.KEYCODE_BUTTON_R2 -> Btn.R2
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_TAB -> Btn.Start
        KeyEvent.KEYCODE_BUTTON_SELECT -> Btn.Select
        // кнопка Home / Guide на геймпаді (у GameSir — кнопка з логотипом)
        KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_F1 -> Btn.Guide
        KeyEvent.KEYCODE_BUTTON_THUMBL -> Btn.L3
        KeyEvent.KEYCODE_BUTTON_THUMBR -> Btn.R3
        else -> null
    }
}
