package io.github.c4software.gliff.android

import android.app.Activity
import android.content.res.Configuration
import android.hardware.input.InputManager
import android.os.Build
import android.view.InputDevice
import android.view.WindowInsets
import android.view.WindowInsetsController

/** The window is a desktop one, which the system gives a header bar. */
internal val Activity.inDesktopWindow: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        (window.decorView.rootWindowInsets?.getInsets(WindowInsets.Type.captionBar())?.top ?: 0) > 0

/**
 * The header bar of a desktop window shows what the app draws under it, the
 * app's black, with light controls: the window reads as one block.
 */
internal fun Activity.blendHeaderBar() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
    // Through the decor view, which this creates: before it, in onCreate,
    // the window's own insetsController throws.
    window.decorView.windowInsetsController?.setSystemBarsAppearance(
        WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND,
        WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND or
            WindowInsetsController.APPEARANCE_LIGHT_CAPTION_BARS,
    )
}

/**
 * A mouse and a keyboard are plugged in: the screen's floating buttons, made
 * for a finger and an on-screen keyboard, have nothing left to do.
 */
internal fun Activity.hasMouseAndKeyboard(): Boolean {
    val config = resources.configuration
    val keyboard = config.keyboard == Configuration.KEYBOARD_QWERTY &&
        config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
    val inputs = getSystemService(InputManager::class.java) ?: return false
    // Built in or plugged, as a Googlebook's touchpad is built in; a touch
    // screen is no mouse. Android's own virtual devices do not count.
    val mouse = inputs.inputDeviceIds.any { id ->
        val device = InputDevice.getDevice(id)
        device != null && !device.isVirtual && device.supportsSource(InputDevice.SOURCE_MOUSE)
    }
    return keyboard && mouse
}
