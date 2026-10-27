package io.github.c4software.gliff.di

import android.content.Context
import io.github.c4software.gliff.data.Logger
import io.github.c4software.gliff.data.clipboard.AndroidClipboard
import io.github.c4software.gliff.data.clipboard.ClipboardWriter
import io.github.c4software.gliff.data.local.KeyValueStore
import io.github.c4software.gliff.data.local.PreferencesStore
import io.github.c4software.gliff.data.mirror.AndroidWebMirror
import io.github.c4software.gliff.data.mirror.WebMirror
import io.github.c4software.gliff.data.preview.FilePreviews
import io.github.c4software.gliff.data.preview.Previews
import io.github.c4software.gliff.data.security.AndroidIdentity
import io.github.c4software.gliff.data.session.Connector
import io.github.c4software.gliff.data.session.SshConnector
import io.github.c4software.gliff.data.video.MediaCodecDecoder
import io.github.c4software.gliff.data.video.VideoDecoderFactory
import io.github.c4software.gliff.domain.identity.Identity
import org.koin.dsl.bind
import org.koin.dsl.module

/** What Android provides to the graph, see [appModules]. */
fun androidModule(context: Context) = module {
    single<Context> { context.applicationContext }
    single<KeyValueStore> { PreferencesStore(get()) }
    single { AndroidIdentity(get()) } bind Identity::class
    single { AndroidClipboard(get()) } bind ClipboardWriter::class
    single<Logger> { Logger { message, cause -> android.util.Log.w("gliff", message, cause) } }
    single<Connector> { SshConnector(get()) }
    single { VideoDecoderFactory(::MediaCodecDecoder) }
    single<Previews> { FilePreviews(get(), get(), get<AppDispatchers>().io) }
    single(createdAtStart = true) {
        AndroidWebMirror(get(), get(), get(), get<AppDispatchers>().io, get())
    } bind WebMirror::class
}
