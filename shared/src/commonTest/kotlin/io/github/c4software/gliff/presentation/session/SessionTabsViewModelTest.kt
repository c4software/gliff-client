package io.github.c4software.gliff.presentation.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import io.github.c4software.gliff.data.local.MemoryStore
import io.github.c4software.gliff.data.local.StoredMachineRepository
import io.github.c4software.gliff.domain.machine.Machine
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class SessionTabsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val machines = StoredMachineRepository(MemoryStore())
    private val a = Machine(id = "a", user = "me", host = "laptop")
    private val b = Machine(id = "b", user = "me", host = "nas")
    private val c = Machine(id = "c", user = "me", host = "laptop", headless = false)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        listOf(a, b, c).forEach(machines::save)
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun openingAgainSelectsTheTabItHas() = runTest(dispatcher) {
        val tabs = SessionTabsViewModel(machines)
        backgroundScope.launch(dispatcher) { tabs.uiState.collect {} }
        tabs.open("a")
        tabs.open("b")
        tabs.open("a")
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), tabs.uiState.value.tabs.map { it.machineId })
        assertEquals("a", tabs.uiState.value.selected)
        assertEquals(b, tabs.uiState.value.tabs[1].machine)
    }

    @Test
    fun closingTheSelectedTabBringsItsNeighbourForward() = runTest(dispatcher) {
        val tabs = SessionTabsViewModel(machines)
        backgroundScope.launch(dispatcher) { tabs.uiState.collect {} }
        listOf("a", "b", "c").forEach(tabs::open)
        tabs.select("b")
        tabs.close("b")
        advanceUntilIdle()
        assertEquals("c", tabs.uiState.value.selected)
        tabs.close("c")
        advanceUntilIdle()
        assertEquals("a", tabs.uiState.value.selected)
        tabs.close("a")
        advanceUntilIdle()
        assertNull(tabs.uiState.value.selected)
        assertTrue(tabs.isEmpty)
    }

    @Test
    fun closingATabClearsItsViewModels() {
        val tabs = SessionTabsViewModel(machines)
        tabs.open("a")
        val probe = ViewModelProvider.create(tabs.owner("a"), Factory)[Probe::class]
        tabs.close("a")
        assertTrue(probe.cleared)
    }

    class Probe : ViewModel() {
        var cleared = false
        override fun onCleared() {
            cleared = true
        }
    }

    private object Factory : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T = Probe() as T
    }
}
