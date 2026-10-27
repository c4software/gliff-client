package io.github.c4software.gliff.di

import io.github.c4software.gliff.data.preview.NoPreviews
import io.github.c4software.gliff.data.session.Sessions
import io.github.c4software.gliff.presentation.machines.MachinesViewModel
import io.github.c4software.gliff.presentation.session.SessionTabsViewModel
import io.github.c4software.gliff.presentation.session.SessionViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val presentationModule = module {
    viewModel {
        MachinesViewModel(get(), get(), get(), getOrNull(), get(), getOrNull() ?: NoPreviews, get<Sessions>().open)
    }
    viewModel { SessionTabsViewModel(get()) }
    viewModel { (machineId: String) -> SessionViewModel(machineId, get(), get(), getOrNull()) }
}
