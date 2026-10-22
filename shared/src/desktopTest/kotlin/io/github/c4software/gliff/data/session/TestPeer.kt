package io.github.c4software.gliff.data.session

import io.github.c4software.gliff.data.video.VideoDecoder
import io.github.c4software.gliff.proto.ByteSink
import io.github.c4software.gliff.proto.ByteSource
import io.github.c4software.gliff.proto.ClientMsg
import io.github.c4software.gliff.proto.FrameReader
import io.github.c4software.gliff.proto.FrameWriter
import io.github.c4software.gliff.proto.OutputInfo
import io.github.c4software.gliff.proto.ServerMsg
import io.github.c4software.gliff.proto.SessionInfo
import io.github.c4software.gliff.transport.Transport
import io.github.c4software.gliff.transport.TransportException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** What the session tests share: an in-memory connection, its server end, a decoder that only counts. */

internal class Pipe : ByteSource, ByteSink {
    private val chunks = LinkedBlockingQueue<ByteArray>()
    private var current = ByteArray(0)
    private var pos = 0

    @Volatile
    private var closed = false

    override fun read(dst: ByteArray, offset: Int, length: Int): Int {
        while (pos == current.size) {
            current = chunks.take()
            pos = 0
            if (current === END) {
                chunks.add(END)
                return -1
            }
        }
        val n = minOf(length, current.size - pos)
        current.copyInto(dst, offset, pos, pos + n)
        pos += n
        return n
    }

    override fun write(src: ByteArray, offset: Int, length: Int) {
        if (closed) throw TransportException("pipe closed")
        if (length > 0) chunks.add(src.copyOfRange(offset, offset + length))
    }

    override fun flush() {}

    fun close() {
        closed = true
        chunks.add(END)
    }

    private companion object {
        val END = ByteArray(0)
    }
}

/** The server's end of one connection. */
internal class Peer {
    val toClient = Pipe()
    val toServer = Pipe()
    val reader = FrameReader(toServer, ClientMsg::decode)
    val writer = FrameWriter(toClient)
    val transport = object : Transport {
        override val source: ByteSource = toClient
        override val sink: ByteSink = toServer
        override fun close() {
            toClient.close()
            toServer.close()
        }
    }

    fun expect(): ClientMsg = reader.readMsg()

    fun handshake(config: ServerMsg.StreamConfig): ClientMsg.Hello {
        val hello = assertIs<ClientMsg.Hello>(expect())
        writer.write(
            ServerMsg.HelloAck(
                1, SessionInfo(true, "HEADLESS-2"), listOf(OutputInfo("HEADLESS-2", 1920, 1080, 1000)), emptyList(),
            )
        )
        writer.write(ServerMsg.Ping(1))
        writer.write(config)
        assertEquals(ClientMsg.Pong(1), expect())
        return hello
    }
}

internal class FakeDecoder : VideoDecoder {
    override val maxWidth = 4096
    override val maxHeight = 4096
    override var listener: VideoDecoder.Listener? = null
    val configured = CopyOnWriteArrayList<Pair<Int, Int>>()
    val decoded = LinkedBlockingQueue<Triple<Long, Int, Boolean>>()

    override fun configure(width: Int, height: Int, dual: Boolean) {
        configured.add(width to height)
    }

    override fun decode(frameId: Long, data: ByteArray, aux: ByteArray?, keyframe: Boolean) {
        decoded.add(Triple(frameId, data.size, keyframe))
        listener?.onFrameDone(frameId)
    }

    override fun close() {}
}
