package io.github.c4software.gliff.transport

import io.github.c4software.gliff.proto.ChromaMode
import io.github.c4software.gliff.proto.ClientCaps
import io.github.c4software.gliff.proto.ClientMsg
import io.github.c4software.gliff.proto.Codec
import io.github.c4software.gliff.proto.ServerMsg
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.exitProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts

private const val USAGE = """gliff probe: connect to a gliff server and dump its H.264 stream.

  ./gradlew :transport:probe --args="[user@]host[:port] [options]"

  --headless WxH       ask the server for a private headless output of that size
  --output NAME        mirror this output (default: the focused one)
  --window WxH         a mirror: the window the stream is fitted to, sent at once
  --scale S            display scale sent with the headless size (default 1)
  --out FILE           where the Annex B stream goes (default out.h264)
  --seconds N          how long to run (default 120, 0 = until the server stops)
  --identity FILE      private key (default ~/.ssh/id_ed25519)
  --accept-new         accept a host key that is not in ~/.ssh/known_hosts
  --server-bin PATH    remote gliff-server (default: on PATH)
  --exec "CMD ..."     run the session over this local command instead of the
                       built-in ssh client, e.g. "ssh -T host gliff-server --stdio"

Then: ffplay out.h264"""

private class Options(args: Array<String>) {
    var target: String? = null
    var headless: Pair<Int, Int>? = null
    var window: Pair<Int, Int>? = null
    var output: String? = null
    var scale = 1f
    var out = "out.h264"
    var seconds = 120
    var identity = File(System.getProperty("user.home"), ".ssh/id_ed25519").path
    var acceptNew = false
    var serverBin = "gliff-server"
    var exec: String? = null

    init {
        var i = 0
        fun value(): String = args.getOrNull(++i) ?: fail("${args[i - 1]} needs a value")
        while (i < args.size) {
            when (val a = args[i]) {
                "--window" -> window = value().lowercase().split('x').mapNotNull { it.toIntOrNull() }
                    .takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: fail("--window expects WxH")
                "--headless" -> headless = value().lowercase().split('x').mapNotNull { it.toIntOrNull() }
                    .takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: fail("--headless expects WxH")
                "--output" -> output = value()
                "--scale" -> scale = value().toFloatOrNull() ?: fail("--scale expects a number")
                "--out" -> out = value()
                "--seconds" -> seconds = value().toIntOrNull() ?: fail("--seconds expects a number")
                "--identity" -> identity = value()
                "--accept-new" -> acceptNew = true
                "--server-bin" -> serverBin = value()
                "--exec" -> exec = value()
                "-h", "--help" -> fail(null)
                else -> if (a.startsWith("-") || target != null) fail("unexpected argument $a") else target = a
            }
            i++
        }
        if (target == null && exec == null) fail("no host given")
        if (headless != null && output != null) fail("--headless and --output exclude each other")
    }

    val serverArgs: List<String>
        get() = when {
            output != null -> listOf("--output", output!!)
            headless != null -> listOf("--headless")
            else -> listOf("--output", "auto")
        }
}

private fun fail(message: String?): Nothing {
    if (message != null) System.err.println("error: $message\n")
    System.err.println(USAGE)
    exitProcess(if (message == null) 0 else 2)
}

private fun connect(o: Options): Transport {
    o.exec?.let { return ProcessTransport(it.trim().split(Regex("\\s+"))) }
    val target = SshTarget.parse(o.target!!, System.getProperty("user.name"))
        .copy(serverBin = o.serverBin, serverArgs = o.serverArgs)
    val knownHosts = File(System.getProperty("user.home"), ".ssh/known_hosts")
    val known = if (knownHosts.exists()) OpenSSHKnownHosts(knownHosts) else null
    return Ssh.connect(
        target,
        keys = { it.loadKeys(o.identity) },
        hostKeys = { key ->
            when {
                known?.verify(key.host, key.port, key.publicKey) == true -> true
                o.acceptNew -> true.also { System.err.println("accepting host key ${key.algorithm} ${key.fingerprint}") }
                else -> false
            }
        },
        log = { System.err.println("[server] $it") },
    )
}

fun main(args: Array<String>) {
    val o = Options(args)
    val transport = try {
        connect(o)
    } catch (e: HostKeyRejectedException) {
        System.err.println("${e.message}\nIt is not in ~/.ssh/known_hosts; pass --accept-new if the fingerprint is right.")
        exitProcess(1)
    } catch (e: TransportException) {
        System.err.println("cannot connect: ${e.message}")
        exitProcess(1)
    }
    val file = FileOutputStream(o.out)
    val frames = AtomicInteger()
    val keyframes = AtomicInteger()
    val bytes = AtomicLong()
    val total = AtomicInteger()
    val rttMs = AtomicLong(-1)
    lateinit var session: Session
    val handler = object : SessionHandler {
        override fun onHello(ack: ServerMsg.HelloAck) {
            val outputs = ack.outputs.joinToString { "${it.name} ${it.width}x${it.height}@${it.scaleMilli / 1000.0}" }
            println("hello: ${if (ack.session.headless) "headless" else "mirror"} ${ack.session.output}; outputs: $outputs")
        }

        override fun onStreamConfig(config: ServerMsg.StreamConfig) {
            println(
                "stream: ${config.codec} ${config.chroma} ${config.width}x${config.height} " +
                    "view ${config.viewWidth}x${config.viewHeight} scale ${config.scaleMilli / 1000.0} " +
                    "fps cap ${config.fpsCap} pipeline ${config.pipeline ?: "?"} extradata ${config.extradata.size} B"
            )
        }

        override fun onVideoFrame(frame: ServerMsg.VideoFrame, main: ByteArray, aux: ByteArray?) {
            file.write(main)
            session.send(ClientMsg.FrameAck(frame.frameId, System.currentTimeMillis()))
            frames.incrementAndGet()
            total.incrementAndGet()
            if (frame.keyframe) keyframes.incrementAndGet()
            bytes.addAndGet(main.size.toLong() + (aux?.size ?: 0))
        }

        override fun onPong(pong: ServerMsg.Pong) {
            rttMs.set(System.currentTimeMillis() - pong.t)
        }
    }
    session = Session(
        transport,
        ClientCaps(
            codecs = listOf(Codec.H264),
            maxWidth = 3840,
            maxHeight = 2160,
            chroma = listOf(ChromaMode.Single420),
        ),
        handler,
        Dispatchers.IO,
        // The remote's own keymap, as the app sends it.
        keymap = transport.keymap,
    )
    println("keymap: ${transport.keymap.length} bytes from the remote")
    (o.headless ?: o.window)?.let { (w, h) -> session.send(ClientMsg.Resize(w, h, o.scale)) }
    Runtime.getRuntime().addShutdownHook(Thread { session.close() })
    val status = try {
        runBlocking {
            val stats = launch {
                var elapsed = 0
                while (true) {
                    delay(1000)
                    elapsed++
                    session.send(ClientMsg.Ping(System.currentTimeMillis()))
                    val rtt = rttMs.get().let { if (it < 0) "-" else "$it ms" }
                    println(
                        "%4d s  %3d fps  %6.2f Mbit/s  %d keyframes  rtt %s".format(
                            elapsed, frames.getAndSet(0), bytes.getAndSet(0) * 8 / 1e6, keyframes.getAndSet(0), rtt,
                        )
                    )
                    if (o.seconds in 1..elapsed) session.close()
                }
            }
            try {
                session.run()
            } finally {
                stats.cancel()
            }
        }
        println("done: ${total.get()} frames in ${o.out}")
        if (total.get() > 0) 0 else 1
    } catch (e: IncompatibleException) {
        System.err.println("incompatible: ${e.message}")
        1
    } catch (e: io.github.c4software.gliff.proto.GliffException) {
        System.err.println("session failed: ${e.message ?: e}")
        1
    } finally {
        file.close()
    }
    exitProcess(status)
}
