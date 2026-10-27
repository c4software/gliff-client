package io.github.c4software.gliff.data.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.c4software.gliff.data.video.Thumbnail
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The pictures as JPEG files in the app's own storage, one per machine, read once at start. */
class FilePreviews(context: Context, private val scope: CoroutineScope, private val io: CoroutineDispatcher) : Previews {
    private val dir = File(context.filesDir, "previews")
    private val state = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())

    override val pictures: StateFlow<Map<String, ImageBitmap>> get() = state

    init {
        scope.launch(io) {
            val read = dir.listFiles().orEmpty().mapNotNull { file ->
                BitmapFactory.decodeFile(file.path)?.let { file.nameWithoutExtension to it.asImageBitmap() }
            }.toMap()
            // Pictures saved meanwhile are newer.
            state.update { read + it }
        }
    }

    override fun save(machineId: String, thumbnail: Thumbnail) {
        scope.launch(io) {
            val bitmap = Bitmap.createBitmap(thumbnail.argb, thumbnail.width, thumbnail.height, Bitmap.Config.ARGB_8888)
            state.update { it + (machineId to bitmap.asImageBitmap()) }
            runCatching {
                dir.mkdirs()
                file(machineId).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
            }
        }
    }

    override fun remove(machineId: String) {
        state.update { it - machineId }
        scope.launch(io) { file(machineId).delete() }
    }

    private fun file(machineId: String) = File(dir, "$machineId.jpg")

    private companion object {
        const val QUALITY = 85
    }
}
