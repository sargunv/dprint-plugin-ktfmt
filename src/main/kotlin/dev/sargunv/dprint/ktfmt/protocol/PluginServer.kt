package dev.sargunv.dprint.ktfmt.protocol

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicInteger

/** What the protocol server needs from the plugin. [C] is a resolved configuration. */
interface PluginHandler<C : Any> {
  fun pluginInfo(): String

  fun license(): String

  fun registerConfig(globalJson: String, pluginJson: String): C

  fun diagnostics(config: C): String

  fun fileMatching(config: C): String

  fun resolvedConfig(config: C): String

  fun applyOverrides(config: C, overrideJson: String): C

  /** Returns the formatted text, or null when nothing changed. */
  fun format(path: String, text: ByteArray, config: C): ByteArray?
}

// dprint process plugin protocol, schema version 5:
// https://github.com/dprint/dprint/blob/main/docs/process-plugin-development.md
internal object Kind {
  const val SUCCESS = 0
  const val DATA = 1
  const val ERROR = 2
  const val CLOSE = 3
  const val IS_ALIVE = 4
  const val PLUGIN_INFO = 5
  const val LICENSE = 6
  const val REGISTER_CONFIG = 7
  const val RELEASE_CONFIG = 8
  const val CONFIG_DIAGNOSTICS = 9
  const val FILE_MATCHING = 10
  const val RESOLVED_CONFIG = 11
  const val CHECK_CONFIG_UPDATES = 12
  const val FORMAT = 13
  const val FORMAT_RESPONSE = 14
  const val CANCEL_FORMAT = 15
  const val HOST_FORMAT = 16
}

internal const val SCHEMA_VERSION = 5
internal const val SUCCESS_BYTES = -1 // 0xFFFFFFFF

private class ProtocolException(message: String) : IOException(message)

/**
 * Serves one dprint CLI connection. Messages are read and answered on the calling thread; formats
 * run on a pool of [parallelism] threads.
 */
class PluginServer<C : Any>(
  input: InputStream,
  output: OutputStream,
  private val handler: PluginHandler<C>,
  parallelism: Int = Runtime.getRuntime().availableProcessors(),
) {
  private val input = DataInputStream(BufferedInputStream(input, 64 * 1024))
  private val writer = MessageWriter(output)
  private val configs = ConcurrentHashMap<Int, C>()
  private val inFlight = ConcurrentHashMap<Int, FutureTask<Unit>>()
  private val pool =
    Executors.newFixedThreadPool(parallelism) { r ->
      Thread(r, "ktfmt-format").apply { isDaemon = true }
    }

  /** Serves messages until the CLI closes the connection and returns the process exit code. */
  fun run(): Int =
    try {
      establishSchema()
      serve()
    } catch (e: IOException) {
      System.err.println("dprint-plugin-ktfmt: ${e.message ?: e}")
      1
    } finally {
      pool.shutdownNow()
    }

  private fun establishSchema() {
    // Raw u32s, not messages: the CLI asks with 0, we answer 0 (success) then the version.
    if (input.readInt() != 0) throw ProtocolException("Expected a schema version request of 0")
    writer.raw(0, SCHEMA_VERSION)
  }

  private fun serve(): Int {
    while (true) {
      val id =
        try {
          input.readInt()
        } catch (_: EOFException) {
          return 0
        }
      handle(id, input.readInt())?.let {
        return it
      }
    }
  }

  /** Handles one message and returns an exit code if the server should stop. */
  private fun handle(id: Int, kind: Int): Int? {
    when (kind) {
      Kind.SUCCESS -> {
        input.readInt()
        end()
      }
      Kind.DATA -> {
        input.readInt()
        input.sized()
        end()
      }
      Kind.ERROR -> {
        val target = input.readInt()
        val message = input.sized().decodeToString()
        end()
        System.err.println("Received error from CLI for message $target: $message")
      }
      Kind.CLOSE -> {
        end()
        writer.success(id)
        return 0
      }
      Kind.IS_ALIVE -> {
        end()
        writer.success(id)
      }
      Kind.PLUGIN_INFO -> {
        end()
        data(id) { handler.pluginInfo() }
      }
      Kind.LICENSE -> {
        end()
        data(id) { handler.license() }
      }
      Kind.REGISTER_CONFIG -> {
        val configId = input.readInt()
        val global = input.sized().decodeToString()
        val plugin = input.sized().decodeToString()
        end()
        reply(id) {
          configs[configId] = handler.registerConfig(global, plugin)
          writer.success(id)
        }
      }
      Kind.RELEASE_CONFIG -> {
        configs.remove(input.readInt())
        end()
        writer.success(id)
      }
      Kind.CONFIG_DIAGNOSTICS -> {
        val configId = input.readInt()
        end()
        data(id) { handler.diagnostics(config(configId)) }
      }
      Kind.FILE_MATCHING -> {
        val configId = input.readInt()
        end()
        data(id) { handler.fileMatching(config(configId)) }
      }
      Kind.RESOLVED_CONFIG -> {
        val configId = input.readInt()
        end()
        data(id) { handler.resolvedConfig(config(configId)) }
      }
      Kind.CHECK_CONFIG_UPDATES -> {
        input.sized()
        end()
        data(id) { """{"changes":[]}""" }
      }
      Kind.FORMAT -> {
        val path = input.sized().decodeToString()
        val start = input.readInt()
        val endIndex = input.readInt()
        val configId = input.readInt()
        val override = input.sized().decodeToString()
        val text = input.sized()
        end()
        val config = configs[configId]
        when {
          config == null -> writer.error(id, configNotFound(configId))
          // Range formatting isn't supported yet; "no change" is always a valid answer.
          start != 0 || endIndex != text.size -> writer.formatResponse(id, null)
          else -> startFormat(id, path, text, config, override)
        }
      }
      Kind.FORMAT_RESPONSE -> {
        // Only sent in reply to a host format, which we never request.
        input.readInt()
        when (val responseKind = input.readInt()) {
          0 -> {}
          1 -> input.sized()
          else -> throw ProtocolException("Unknown format response kind: $responseKind")
        }
        end()
      }
      Kind.CANCEL_FORMAT -> {
        val target = input.readInt()
        end()
        // Queued formats never start. ktfmt doesn't check for interrupts, so a running one
        // finishes, but its response is dropped.
        inFlight.remove(target)?.cancel(false)
      }
      Kind.HOST_FORMAT -> {
        input.readInt()
        input.sized()
        input.readInt()
        input.readInt()
        input.sized()
        input.sized()
        end()
        writer.error(id, "Cannot host format with a plugin.")
      }
      else -> {
        // The rest of the message can't be parsed, so the protocol says to exit.
        System.err.println("dprint-plugin-ktfmt: unknown message kind $kind")
        return 1
      }
    }
    return null
  }

  private fun startFormat(id: Int, path: String, text: ByteArray, config: C, override: String) {
    val task = FutureTask {
      val result = runCatching {
        val effective =
          if (override.isBlank() || override == "{}") config
          else handler.applyOverrides(config, override)
        handler.format(path, text, effective)
      }
      // Whoever removes the entry first wins: this task, or a CancelFormat that suppresses it.
      if (inFlight.remove(id) != null) {
        result.fold({ writer.formatResponse(id, it) }, { writer.error(id, it.describe()) })
      }
    }
    inFlight[id] = task
    pool.execute(task)
  }

  private fun config(configId: Int): C =
    configs[configId] ?: throw NoSuchElementException(configNotFound(configId))

  private fun configNotFound(configId: Int) =
    "Did not find configuration for id: ${configId.toUInt()}"

  private inline fun reply(id: Int, action: () -> Unit) {
    try {
      action()
    } catch (e: IOException) {
      throw e
    } catch (e: Exception) {
      writer.error(id, e.describe())
    }
  }

  private inline fun data(id: Int, json: () -> String) =
    reply(id) {
      writer.data(id, json().encodeToByteArray())
    }

  private fun end() {
    if (input.readInt() != SUCCESS_BYTES) {
      throw ProtocolException("Did not receive success bytes at end of message")
    }
  }
}

private fun Throwable.describe(): String = message ?: toString()

private fun DataInputStream.sized(): ByteArray = ByteArray(readInt()).also(::readFully)

/** Serializes whole messages onto the output, which carries nothing but the protocol. */
private class MessageWriter(stream: OutputStream) {
  private val out = DataOutputStream(BufferedOutputStream(stream, 64 * 1024))
  private val nextId = AtomicInteger(0)

  @Synchronized
  fun raw(vararg values: Int) {
    values.forEach(out::writeInt)
    out.flush()
  }

  @Synchronized
  private fun send(kind: Int, body: DataOutputStream.() -> Unit) {
    out.writeInt(nextId.getAndIncrement())
    out.writeInt(kind)
    out.body()
    out.writeInt(SUCCESS_BYTES)
    out.flush()
  }

  fun success(id: Int) = send(Kind.SUCCESS) { writeInt(id) }

  fun data(id: Int, bytes: ByteArray) =
    send(Kind.DATA) {
      writeInt(id)
      sized(bytes)
    }

  fun error(id: Int, message: String) =
    send(Kind.ERROR) {
      writeInt(id)
      sized(message.encodeToByteArray())
    }

  fun formatResponse(id: Int, bytes: ByteArray?) =
    send(Kind.FORMAT_RESPONSE) {
      writeInt(id)
      if (bytes == null) writeInt(0)
      else {
        writeInt(1)
        sized(bytes)
      }
    }

  private fun DataOutputStream.sized(bytes: ByteArray) {
    writeInt(bytes.size)
    write(bytes)
  }
}
