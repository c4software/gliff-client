package io.github.c4software.gliff.data.video

import io.github.c4software.gliff.ios.withLock
import io.github.c4software.gliff.mirror.Fmp4
import kotlin.concurrent.Volatile
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.AVFoundation.AVLayerVideoGravityResize
import platform.AVFoundation.AVQueuedSampleBufferRenderingStatusFailed
import platform.AVFoundation.AVSampleBufferDisplayLayer
import platform.AVFoundation.enqueueSampleBuffer
import platform.AVFoundation.flush
import platform.AVFoundation.status
import platform.CoreFoundation.CFArrayGetValueAtIndex
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreMedia.CMBlockBufferCreateWithMemoryBlock
import platform.CoreMedia.CMBlockBufferRefVar
import platform.CoreMedia.CMBlockBufferReplaceDataBytes
import platform.CoreMedia.CMClockGetHostTimeClock
import platform.CoreMedia.CMClockGetTime
import platform.CoreMedia.CMFormatDescriptionRefVar
import platform.CoreMedia.CMSampleBufferCreateReady
import platform.CoreMedia.CMSampleBufferCreateReadyWithImageBuffer
import platform.CoreMedia.CMSampleBufferGetSampleAttachmentsArray
import platform.CoreMedia.CMSampleBufferRefVar
import platform.CoreMedia.CMSampleTimingInfo
import platform.CoreMedia.CMVideoFormatDescriptionCreateForImageBuffer
import platform.CoreMedia.CMVideoFormatDescriptionCreateFromH264ParameterSets
import platform.CoreMedia.CMVideoFormatDescriptionMatchesImageBuffer
import platform.CoreMedia.CMVideoFormatDescriptionRef
import platform.CoreMedia.kCMBlockBufferAssureMemoryNowFlag
import platform.CoreMedia.kCMSampleAttachmentKey_DisplayImmediately
import platform.CoreVideo.CVImageBufferRef
import platform.Foundation.NSLock
import platform.Foundation.NSLog
import platform.VideoToolbox.VTDecompressionSessionCreate
import platform.VideoToolbox.VTDecompressionSessionDecodeFrameWithOutputHandler
import platform.VideoToolbox.VTDecompressionSessionInvalidate
import platform.VideoToolbox.VTDecompressionSessionRef
import platform.VideoToolbox.VTDecompressionSessionRefVar
import platform.VideoToolbox.VTSessionSetProperty
import platform.VideoToolbox.kVTDecodeFrame_EnableAsynchronousDecompression
import platform.VideoToolbox.kVTDecompressionPropertyKey_RealTime
import platform.posix.size_tVar

/**
 * H.264 through VideoToolbox, shown on an `AVSampleBufferDisplayLayer`.
 *
 * The stream comes as Annex B with its parameter sets in front of every
 * keyframe. A decompression session is made from those sets, and made again
 * when they change; frames go in as AVCC, and each picture that comes out is
 * handed to the layer to show at once.
 *
 * The layer is there for as long as the decoder, so unlike a surface on
 * Android nothing has to appear before frames can be taken. What does go away
 * is the session, which the system invalidates when the app leaves the
 * screen: the frame that finds it gone is acked, and a keyframe asked for.
 */
@OptIn(ExperimentalForeignApi::class)
class VideoToolboxDecoder : VideoDecoder {
    // What the hardware decodes in H.264. A phone held upright wants the long
    // side vertical: offer a square, as on Android.
    override val maxWidth: Int = 4096
    override val maxHeight: Int = 4096

    @Volatile
    override var listener: VideoDecoder.Listener? = null

    /** Where the pictures go. It stretches them to its bounds: the layout alone decides their size. */
    val layer = AVSampleBufferDisplayLayer().apply { videoGravity = AVLayerVideoGravityResize }

    /** Guards the session and what is owed; never held across a call into VideoToolbox. */
    private val lock = NSLock()
    private var sets: Fmp4.ParameterSets? = null
    private var format: CMVideoFormatDescriptionRef? = null
    private var session: VTDecompressionSessionRef? = null

    /** Counts the sessions, so that a late failure of an old one is not taken for the current. */
    private var generation = 0

    /** Frames inside the session, still owed an ack. */
    private val pending = HashSet<Long>()
    private var waitingForKeyframe = true

    /** Guards the format of the pictures shown; the output side only. */
    private val showLock = NSLock()
    private var shownFormat: CMVideoFormatDescriptionRef? = null

    /** A session taken out of use, with the frames it will never give back. */
    private class Retired(val session: VTDecompressionSessionRef?, val format: CMVideoFormatDescriptionRef?, val owed: List<Long>)

    // One 4:2:0 stream: fullChroma stays false, so the server never sends an auxiliary one.
    override fun configure(width: Int, height: Int, dual: Boolean) {
        // The server opens a new coded size with a keyframe by itself.
        dispose(lock.withLock { retire() })
    }

    override fun decode(frameId: Long, data: ByteArray, aux: ByteArray?, keyframe: Boolean) {
        var retired: Retired? = null
        var failed = false
        val accepted = lock.withLock {
            if (keyframe) {
                val found = H264.parameterSets(data)
                if (found != null && (session == null || found != sets)) {
                    retired = retire()
                    failed = !start(found)
                }
            }
            if (session == null || (waitingForKeyframe && !keyframe)) {
                null
            } else {
                pending.add(frameId)
                waitingForKeyframe = false
                Triple(session, format, generation)
            }
        }
        retired?.let(::dispose)
        if (accepted == null) {
            listener?.onFrameDone(frameId)
            // A keyframe that could not start a session: another may.
            if (failed) listener?.onNeedKeyframe()
            return
        }
        val (session, format, generation) = accepted
        val status = submit(session, format, frameId, H264.avcc(data), generation)
        if (status != 0) {
            NSLog("gliff: decode of frame %lld refused: %d", frameId, status)
            done(frameId)
            fail(generation)
        }
    }

    override fun close() {
        dispose(lock.withLock { retire() })
        showLock.withLock {
            shownFormat?.let { CFRelease(it) }
            shownFormat = null
        }
    }

    /** Make a session for these parameter sets. Under the lock. */
    private fun start(found: Fmp4.ParameterSets): Boolean = memScoped {
        val formatOut = alloc<CMFormatDescriptionRefVar>()
        val described = found.sps.asUByteArray().usePinned { sps ->
            found.pps.asUByteArray().usePinned { pps ->
                val pointers = allocArray<CPointerVar<UByteVar>>(2)
                pointers[0] = sps.addressOf(0)
                pointers[1] = pps.addressOf(0)
                val sizes = allocArray<size_tVar>(2)
                sizes[0] = found.sps.size.convert()
                sizes[1] = found.pps.size.convert()
                CMVideoFormatDescriptionCreateFromH264ParameterSets(
                    kCFAllocatorDefault, 2u, pointers, sizes, AVCC_LENGTH_BYTES, formatOut.ptr,
                )
            }
        }
        val made = formatOut.value
        if (described != 0 || made == null) {
            NSLog("gliff: cannot read the stream's parameter sets: %d", described)
            return false
        }
        val sessionOut = alloc<VTDecompressionSessionRefVar>()
        // No output callback: each frame is submitted with its own handler.
        val created = VTDecompressionSessionCreate(kCFAllocatorDefault, made, null, null, null, sessionOut.ptr)
        val opened = sessionOut.value
        if (created != 0 || opened == null) {
            NSLog("gliff: cannot start a decoder: %d", created)
            CFRelease(made)
            return false
        }
        VTSessionSetProperty(opened, kVTDecompressionPropertyKey_RealTime, kCFBooleanTrue)
        sets = found
        format = made
        session = opened
        generation++
        true
    }

    /** Take the session out of use. Under the lock; [dispose] of the result outside. */
    private fun retire(): Retired {
        val retired = Retired(session, format, pending.toList())
        session = null
        format = null
        sets = null
        pending.clear()
        waitingForKeyframe = true
        return retired
    }

    private fun dispose(retired: Retired) {
        retired.session?.let {
            VTDecompressionSessionInvalidate(it)
            CFRelease(it)
        }
        retired.format?.let { CFRelease(it) }
        // Frames the old session swallowed will never come out.
        retired.owed.forEach { listener?.onFrameDone(it) }
    }

    /** Hand one frame to the session; the status is 0 when it took it. */
    private fun submit(
        session: VTDecompressionSessionRef?,
        format: CMVideoFormatDescriptionRef?,
        frameId: Long,
        avcc: ByteArray,
        generation: Int,
    ): Int = memScoped {
        if (avcc.isEmpty()) return@memScoped NOTHING_TO_DECODE
        val blockOut = alloc<CMBlockBufferRefVar>()
        var status = CMBlockBufferCreateWithMemoryBlock(
            kCFAllocatorDefault, null, avcc.size.convert(), kCFAllocatorDefault, null, 0u, avcc.size.convert(),
            kCMBlockBufferAssureMemoryNowFlag, blockOut.ptr,
        )
        val block = blockOut.value
        if (status != 0 || block == null) return@memScoped status.takeIf { it != 0 } ?: NOTHING_TO_DECODE
        try {
            status = avcc.usePinned { CMBlockBufferReplaceDataBytes(it.addressOf(0), block, 0u, avcc.size.convert()) }
            if (status != 0) return@memScoped status
            val size = alloc<size_tVar>()
            size.value = avcc.size.convert()
            val sampleOut = alloc<CMSampleBufferRefVar>()
            status = CMSampleBufferCreateReady(kCFAllocatorDefault, block, format, 1, 0, null, 1, size.ptr, sampleOut.ptr)
            val sample = sampleOut.value
            if (status != 0 || sample == null) return@memScoped status.takeIf { it != 0 } ?: NOTHING_TO_DECODE
            try {
                VTDecompressionSessionDecodeFrameWithOutputHandler(
                    session, sample, kVTDecodeFrame_EnableAsynchronousDecompression, null,
                ) { result, _, image, _, _ ->
                    // Shown as soon as possible: the newest frame is the right one.
                    if (result == 0 && image != null) show(image)
                    done(frameId)
                    if (result != 0) {
                        NSLog("gliff: frame %lld not decoded: %d", frameId, result)
                        fail(generation)
                    }
                }
            } finally {
                CFRelease(sample)
            }
        } finally {
            CFRelease(block)
        }
    }

    /** The frame left the decoder, or was dropped: ack it, once. */
    private fun done(frameId: Long) {
        if (lock.withLock { pending.remove(frameId) }) listener?.onFrameDone(frameId)
    }

    /** The session of `generation` cannot go on: start over from the next keyframe. */
    private fun fail(generation: Int) {
        val retired = lock.withLock { if (this.generation == generation && session != null) retire() else null }
        retired?.let(::dispose)
        listener?.onNeedKeyframe()
    }

    /** Put a decoded picture on the layer. Called on VideoToolbox's thread. */
    private fun show(image: CVImageBufferRef) = memScoped {
        val format = showLock.withLock {
            val current = shownFormat
            if (current != null && CMVideoFormatDescriptionMatchesImageBuffer(current, image)) {
                current
            } else {
                current?.let { CFRelease(it) }
                val out = alloc<CMFormatDescriptionRefVar>()
                CMVideoFormatDescriptionCreateForImageBuffer(kCFAllocatorDefault, image, out.ptr)
                out.value.also { shownFormat = it }
            }
        } ?: return@memScoped
        val timing = alloc<CMSampleTimingInfo>()
        // No duration and no decoding time: flags left at 0 make a time invalid.
        timing.duration.apply { value = 0; timescale = 0; flags = 0u; epoch = 0 }
        timing.decodeTimeStamp.apply { value = 0; timescale = 0; flags = 0u; epoch = 0 }
        CMClockGetTime(CMClockGetHostTimeClock()).place(timing.presentationTimeStamp.ptr)
        val sampleOut = alloc<CMSampleBufferRefVar>()
        val status = CMSampleBufferCreateReadyWithImageBuffer(kCFAllocatorDefault, image, format, timing.ptr, sampleOut.ptr)
        val sample = sampleOut.value
        if (status != 0 || sample == null) return@memScoped
        // Whatever its timestamp says, the picture is for now.
        CMSampleBufferGetSampleAttachmentsArray(sample, true)?.let { attachments ->
            val first: CFMutableDictionaryRef? = CFArrayGetValueAtIndex(attachments, 0)?.reinterpret()
            CFDictionarySetValue(first, kCMSampleAttachmentKey_DisplayImmediately, kCFBooleanTrue)
        }
        // A layer that failed (the app came back from the background) takes nothing until flushed.
        if (layer.status == AVQueuedSampleBufferRenderingStatusFailed) layer.flush()
        layer.enqueueSampleBuffer(sample)
        CFRelease(sample)
    }

    private companion object {
        const val AVCC_LENGTH_BYTES = 4

        /** Our own status for a frame with nothing in it to decode. */
        const val NOTHING_TO_DECODE = -1
    }
}
