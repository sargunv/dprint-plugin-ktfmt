package dev.sargunv.dprint.ktfmt.protocol

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.channels.Channels
import java.nio.channels.Pipe
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** A message from the plugin. [body] is the data, error text, or formatted text, if any. */
data class Response(val id: Int, val kind: Int, val target: Int, val body: String? = null)

/** Plays the dprint CLI's side of the protocol against an in-process [PluginServer]. */
class TestClient<C : Any>(handler: PluginHandler<C>, parallelism: Int = 4) : AutoCloseable {
  private val toServer = Pipe.open()
  private val fromServer = Pipe.open()
  val output = DataOutputStream(Channels.newOutputStream(toServer.sink()))
  val input = DataInputStream(Channels.newInputStream(fromServer.source()))
  private val responses = LinkedBlockingQueue<Result<Response>>()
  private var nextId = 1

  private val server =
    PluginServer(
      Channels.newInputStream(toServer.source()),
      Channels.newOutputStream(fromServer.sink()),
      handler,
      parallelism,
    )
  private val exit = CompletableFuture<Int>()

  init {
    Thread({ exit.complete(server.run()) }, "test-server").apply { isDaemon = true }.start()
  }

  fun handshake() {
    output.writeInt(0)
    output.flush()
    assertEquals(0, input.readInt())
    assertEquals(SCHEMA_VERSION, input.readInt())
    Thread(::readResponses, "test-reader").apply { isDaemon = true }.start()
  }

  fun send(kind: Int, body: DataOutputStream.() -> Unit = {}): Int {
    val id = nextId++
    output.writeInt(id)
    output.writeInt(kind)
    output.body()
    output.writeInt(SUCCESS_BYTES)
    output.flush()
    return id
  }

  fun receive(): Response =
    assertNotNull(responses.poll(10, TimeUnit.SECONDS), "timed out waiting for a response")
      .getOrThrow()

  /** Sends a message and returns the next response, checking that it answers this message. */
  fun request(kind: Int, body: DataOutputStream.() -> Unit = {}): Response {
    val id = send(kind, body)
    return receive().also { assertEquals(id, it.target, "response target") }
  }

  /** Proves every earlier message was handled without a response we didn't expect. */
  fun roundTrip() = assertEquals(Kind.SUCCESS, request(Kind.IS_ALIVE).kind)

  fun registerConfig(configId: Int, global: String = "{}", plugin: String = "{}") =
    request(Kind.REGISTER_CONFIG) {
      writeInt(configId)
      sized(global)
      sized(plugin)
    }

  fun sendFormat(
    path: String,
    text: String,
    configId: Int = 1,
    override: String = "",
    range: Pair<Int, Int>? = null,
  ): Int {
    val bytes = text.encodeToByteArray()
    return send(Kind.FORMAT) {
      sized(path)
      writeInt(range?.first ?: 0)
      writeInt(range?.second ?: bytes.size)
      writeInt(configId)
      sized(override)
      writeInt(bytes.size)
      write(bytes)
    }
  }

  fun format(
    path: String,
    text: String,
    configId: Int = 1,
    override: String = "",
    range: Pair<Int, Int>? = null,
  ): Response {
    val id = sendFormat(path, text, configId, override, range)
    return receive().also { assertEquals(id, it.target, "response target") }
  }

  fun exitCode(): Int = exit.get(10, TimeUnit.SECONDS)

  override fun close() {
    toServer.sink().close()
    fromServer.source().close()
  }

  private fun readResponses() {
    try {
      while (true) {
        val id = input.readInt()
        val kind = input.readInt()
        val target = input.readInt()
        val body =
          when (kind) {
            Kind.SUCCESS -> null
            Kind.DATA,
            Kind.ERROR -> input.sized()
            Kind.FORMAT_RESPONSE -> if (input.readInt() == 1) input.sized() else null
            else -> error("unexpected message kind $kind from plugin")
          }
        assertEquals(SUCCESS_BYTES, input.readInt(), "success bytes")
        responses.put(Result.success(Response(id, kind, target, body)))
      }
    } catch (_: EOFException) {} catch (e: Throwable) {
      responses.put(Result.failure(e))
    }
  }
}

fun DataOutputStream.sized(text: String) {
  val bytes = text.encodeToByteArray()
  writeInt(bytes.size)
  write(bytes)
}

private fun DataInputStream.sized(): String =
  ByteArray(readInt()).also(::readFully).decodeToString()
