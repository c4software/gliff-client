package io.github.c4software.gliff.presentation.machines

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.c4software.gliff.data.mirror.WebMirror
import io.github.c4software.gliff.data.preview.NoPreviews
import io.github.c4software.gliff.data.preview.Previews
import io.github.c4software.gliff.di.AppDispatchers
import io.github.c4software.gliff.domain.identity.Identity
import io.github.c4software.gliff.domain.machine.Machine
import io.github.c4software.gliff.domain.machine.MachineRepository
import io.github.c4software.gliff.domain.settings.Settings
import io.github.c4software.gliff.domain.settings.SettingsRepository
import io.github.c4software.gliff.presentation.UiStateSharing
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MachinesUiState(
    val machines: List<Machine> = emptyList(),
    val settings: Settings = Settings(),
    /** Where the web mirror answers, empty while it is off; null where there is no mirror. */
    val mirrorUrls: List<String>? = null,
    /** The line for `authorized_keys`, null until it has been read. */
    val authorizedKey: String? = null,
    /** What each machine's screen last looked like, by machine id. */
    val previews: Map<String, ImageBitmap> = emptyMap(),
    /** The machines whose session is open. */
    val open: Set<String> = emptySet(),
)

class MachinesViewModel(
    private val machines: MachineRepository,
    private val settings: SettingsRepository,
    private val identity: Identity,
    mirror: WebMirror?,
    private val dispatchers: AppDispatchers,
    private val previews: Previews = NoPreviews,
    open: Flow<Set<String>> = flowOf(emptySet()),
) : ViewModel() {
    private val authorizedKey = MutableStateFlow<String?>(null)

    // The network's addresses are listed again with each change of the options.
    private val mirrorUrls: Flow<List<String>?> = mirror?.let {
        settings.settings
            .map { options -> if (options.httpMirror) it.urls(options.mirrorToken) else emptyList() }
            .flowOn(dispatchers.io)
    } ?: flowOf(null)

    private val sessions = combine(previews.pictures, open, ::Pair)

    val uiState: StateFlow<MachinesUiState> =
        combine(machines.machines, settings.settings, mirrorUrls, authorizedKey, sessions) { all, options, urls, key, (pictures, opened) ->
            MachinesUiState(all, options, urls, key, pictures, opened)
        }.stateIn(
            viewModelScope,
            UiStateSharing,
            MachinesUiState(machines.machines.value, settings.settings.value, previews = previews.pictures.value),
        )

    fun save(machine: Machine) = machines.save(machine)

    fun remove(machine: Machine) {
        machines.remove(machine.id)
        previews.remove(machine.id)
    }

    fun updateSettings(changed: Settings) = settings.update { changed }

    /** Read this device's key, made on first use, which takes a moment. */
    fun loadKey() {
        if (authorizedKey.value != null) return
        viewModelScope.launch { authorizedKey.value = withContext(dispatchers.io) { identity.authorizedKey() } }
    }
}
