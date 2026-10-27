package io.github.c4software.gliff.presentation.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import io.github.c4software.gliff.domain.machine.Machine
import io.github.c4software.gliff.domain.machine.MachineRepository
import io.github.c4software.gliff.presentation.UiStateSharing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** One open session; `machine` is null once the machine was deleted. */
data class SessionTab(val machineId: String, val machine: Machine?)

data class SessionTabsUiState(
    val tabs: List<SessionTab> = emptyList(),
    /** The tab on screen, null once the last one is closed. */
    val selected: String? = null,
    /** The machines' list is on screen instead, to open another one. */
    val listing: Boolean = false,
)

/**
 * The sessions open in one window, one tab each. Each tab keeps its own
 * ViewModels, the session's among them, until it is closed or the window is
 * gone for good: clearing them ends its session.
 */
class SessionTabsViewModel(machines: MachineRepository) : ViewModel() {
    private val ids = MutableStateFlow<List<String>>(emptyList())
    private val selected = MutableStateFlow<String?>(null)
    private val listing = MutableStateFlow(false)
    private val owners = mutableMapOf<String, ViewModelStoreOwner>()

    val uiState: StateFlow<SessionTabsUiState> =
        combine(ids, selected, listing, machines.machines) { open, current, list, all ->
            SessionTabsUiState(open.map { id -> SessionTab(id, all.firstOrNull { it.id == id }) }, current, list)
        }.stateIn(viewModelScope, UiStateSharing, SessionTabsUiState())

    /** No tab is open. */
    val isEmpty: Boolean get() = ids.value.isEmpty()

    /** Open a tab for `machineId`, or bring its tab forward. */
    fun open(machineId: String) {
        if (machineId !in ids.value) ids.value += machineId
        selected.value = machineId
        listing.value = false
    }

    fun select(machineId: String) {
        if (machineId !in ids.value) return
        selected.value = machineId
        listing.value = false
    }

    /** Show the machines' list, or (false) the selected tab again. */
    fun showList(shown: Boolean) {
        listing.value = shown
    }

    /** Close `machineId`'s tab, ending its session; the one next to it comes forward. */
    fun close(machineId: String) {
        val before = ids.value
        val at = before.indexOf(machineId)
        if (at < 0) return
        val after = before - machineId
        ids.value = after
        if (selected.value == machineId) selected.value = after.getOrNull(at) ?: after.lastOrNull()
        owners.remove(machineId)?.viewModelStore?.clear()
    }

    /** Where `machineId`'s tab keeps its ViewModels. */
    fun owner(machineId: String): ViewModelStoreOwner = owners.getOrPut(machineId) {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }

    override fun onCleared() {
        owners.values.forEach { it.viewModelStore.clear() }
        owners.clear()
    }
}
