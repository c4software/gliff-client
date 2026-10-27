package io.github.c4software.gliff.android

import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import android.view.Menu
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import io.github.c4software.gliff.presentation.machines.MachinesCommand
import io.github.c4software.gliff.presentation.machines.MachinesRoute
import io.github.c4software.gliff.presentation.theme.English
import io.github.c4software.gliff.presentation.theme.French
import io.github.c4software.gliff.presentation.theme.GliffTheme
import io.github.c4software.gliff.presentation.theme.Strings
import java.util.Locale
import kotlinx.coroutines.flow.MutableSharedFlow

/** The list of machines. Each machine opens in a window of its own. */
class MainActivity : ComponentActivity() {
    private val commands = MutableSharedFlow<MachinesCommand>(extraBufferCapacity = 1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        blendHeaderBar()
        setContent {
            GliffTheme {
                MachinesRoute(
                    onOpen = { startActivity(SessionActivity.intent(this, it.id)) },
                    commands = commands,
                    desktop = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM,
                )
            }
        }
    }

    override fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean {
        val command = SHORTCUTS.firstOrNull { it.keyCode == keyCode && event.hasModifiers(KeyEvent.META_CTRL_ON) }
            ?: return super.onKeyShortcut(keyCode, event)
        commands.tryEmit(command.command)
        return true
    }

    /** The shortcuts, listed by the system's keyboard shortcuts helper (Meta + /). */
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
        val strings = if (Locale.getDefault().language == "fr") French else English
        data += KeyboardShortcutGroup(
            strings.appName,
            SHORTCUTS.map { KeyboardShortcutInfo(it.label(strings), it.keyCode, KeyEvent.META_CTRL_ON) },
        )
    }

    private class Shortcut(val keyCode: Int, val command: MachinesCommand, val label: (Strings) -> String)

    private companion object {
        val SHORTCUTS = listOf(
            Shortcut(KeyEvent.KEYCODE_N, MachinesCommand.Add) { it.addMachine },
            Shortcut(KeyEvent.KEYCODE_COMMA, MachinesCommand.Options) { it.options },
        )
    }
}
