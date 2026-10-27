package io.github.c4software.gliff.di

import io.github.c4software.gliff.data.local.StoredMachineRepository
import io.github.c4software.gliff.data.local.StoredSettingsRepository
import io.github.c4software.gliff.data.preview.NoPreviews
import io.github.c4software.gliff.data.session.Sessions
import io.github.c4software.gliff.domain.machine.MachineRepository
import io.github.c4software.gliff.domain.settings.SettingsRepository
import org.koin.dsl.module

/**
 * The domain's contracts bound to their implementations. What depends on the
 * platform (the store, ssh, the decoder, the clipboard, the log) comes from
 * the platform's module.
 */
val dataModule = module {
    single<MachineRepository> { StoredMachineRepository(get()) }
    single<SettingsRepository> { StoredSettingsRepository(get()) }
    single {
        Sessions(
            machines = get(),
            settings = get(),
            connector = get(),
            scope = get(),
            io = get<AppDispatchers>().io,
            clock = get(),
            clipboard = get(),
            logger = get(),
            decoders = get(),
            previews = getOrNull() ?: NoPreviews,
        )
    }
}
