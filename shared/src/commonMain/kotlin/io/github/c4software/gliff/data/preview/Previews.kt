package io.github.c4software.gliff.data.preview

import androidx.compose.ui.graphics.ImageBitmap
import io.github.c4software.gliff.data.video.Thumbnail
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What each machine's screen last looked like, for the list's tiles. Any thread. */
interface Previews {
    /** The machines' last pictures, by machine id. */
    val pictures: StateFlow<Map<String, ImageBitmap>>

    fun save(machineId: String, thumbnail: Thumbnail)

    fun remove(machineId: String)
}

/** For a platform that keeps no pictures: the tiles show the machines' initials. */
object NoPreviews : Previews {
    override val pictures: StateFlow<Map<String, ImageBitmap>> = MutableStateFlow(emptyMap())

    override fun save(machineId: String, thumbnail: Thumbnail) {}

    override fun remove(machineId: String) {}
}
