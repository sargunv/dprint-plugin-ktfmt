package dev.sargunv.dprint.ktfmt

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.PrintStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

// dprint process plugin protocol, schema version 5:
// https://github.com/dprint/dprint/blob/main/docs/process-plugin-development.md
private object Kind {
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
}

private const val SCHEMA_VERSION = 5
private const val SUCCESS_BYTES = -1 // 0xFFFFFFFF

/** Serializes writes to the real stdout, which carries nothing but protocol messages. */
private class Writer(stream: FileOutputStream) {
  private val out = DataOutputStream(BufferedOutputStream(stream, 64 * 1024))
  private val nextId = AtomicInteger(0)

  @Synchronized
  fun send(kind: Int, body: DataOutputStream.() -> Unit) {
    out.writeInt(nextId.getAndIncrement())
    out.writeInt(kind)
    out.body()
    out.writeInt(SUCCESS_BYTES)
    out.flush()
  }

  @Synchronized
  fun raw(vararg values: Int) {
    values.forEach(out::writeInt)
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
      sized(message.toByteArray())
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
}

private fun DataOutputStream.sized(bytes: ByteArray) {
  writeInt(bytes.size)
  write(bytes)
}

private fun DataInputStream.sized(): ByteArray = ByteArray(readInt()).also(::readFully)

private fun DataInputStream.successBytes() {
  check(readInt() == SUCCESS_BYTES) { "Did not receive success bytes at end of message" }
}

fun main(args: Array<String>) {
  // Take the real stdout for the protocol before anything else can touch it, then point
  // System.out at stderr so stray library or JVM output becomes a dprint warning instead of
  // corrupting the message stream (see sargunv/stringline#1348 for the exec-plugin version of
  // this).
  val writer = Writer(FileOutputStream(FileDescriptor.out))
  System.setOut(PrintStream(FileOutputStream(FileDescriptor.err), true))

  args
    .indexOf("--parent-pid")
    .takeIf { it >= 0 }
    ?.let { args.getOrNull(it + 1)?.toLongOrNull() }
    ?.let(::watchParent)

  val input = DataInputStream(BufferedInputStream(FileInputStream(FileDescriptor.`in`), 64 * 1024))

  // Schema establishment: the CLI sends a raw 0, we answer 0 then the schema version.
  check(input.readInt() == 0) { "Unexpected schema establishment request" }
  writer.raw(0, SCHEMA_VERSION)

  val configs = ConcurrentHashMap<Int, ResolvedConfig>()
  val inFlight = ConcurrentHashMap<Int, Future<*>>()
  val pool =
    Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors()) { r ->
      Thread(r, "ktfmt-format").apply { isDaemon = true }
    }

  while (true) {
    val id =
      try {
        input.readInt()
      } catch (_: EOFException) {
        exitProcess(0)
      }
    when (val kind = input.readInt()) {
      Kind.SUCCESS,
      Kind.CANCEL_FORMAT -> {
        val target = input.readInt()
        input.successBytes()
        if (kind == Kind.CANCEL_FORMAT) inFlight.remove(target)?.cancel(true)
      }
      Kind.ERROR,
      Kind.DATA -> {
        input.readInt()
        input.sized()
        input.successBytes()
      }
      Kind.CLOSE -> {
        input.successBytes()
        writer.success(id)
        exitProcess(0)
      }
      Kind.IS_ALIVE -> {
        input.successBytes()
        writer.success(id)
      }
      Kind.PLUGIN_INFO -> {
        input.successBytes()
        writer.data(id, pluginInfoJson().toByteArray())
      }
      Kind.LICENSE -> {
        input.successBytes()
        writer.data(id, licenseText().toByteArray())
      }
      Kind.REGISTER_CONFIG -> {
        val configId = input.readInt()
        val global = input.sized()
        val plugin = input.sized()
        input.successBytes()
        configs[configId] = resolveConfig(global.decodeToString(), plugin.decodeToString())
        writer.success(id)
      }
      Kind.RELEASE_CONFIG -> {
        configs.remove(input.readInt())
        input.successBytes()
        writer.success(id)
      }
      Kind.CONFIG_DIAGNOSTICS,
      Kind.FILE_MATCHING,
      Kind.RESOLVED_CONFIG -> {
        val config = configs[input.readInt()]
        input.successBytes()
        if (config == null) {
          writer.error(id, "Configuration not registered")
        } else {
          val json =
            when (kind) {
              Kind.CONFIG_DIAGNOSTICS -> config.diagnosticsJson()
              Kind.FILE_MATCHING -> FILE_MATCHING_JSON
              else -> config.resolvedJson()
            }
          writer.data(id, json.toByteArray())
        }
      }
      Kind.CHECK_CONFIG_UPDATES -> {
        input.sized()
        input.successBytes()
        writer.data(id, """{"changes":[]}""".toByteArray())
      }
      Kind.FORMAT -> {
        val path = input.sized().decodeToString()
        val start = input.readInt()
        val end = input.readInt()
        val config = configs[input.readInt()]
        val override = input.sized().decodeToString()
        val text = input.sized()
        input.successBytes()
        inFlight[id] = pool.submit {
          try {
            when {
              config == null -> writer.error(id, "Configuration not registered")
              // Range formatting isn't wired up yet; dprint treats "no change" as a valid answer.
              start != 0 || end != text.size -> writer.formatResponse(id, null)
              else -> {
                val effective =
                  if (override.isBlank() || override == "{}") config
                  else config.withOverrides(override)
                writer.formatResponse(
                  id,
                  formatKotlin(path, text.decodeToString(), effective)?.toByteArray(),
                )
              }
            }
          } catch (e: Throwable) {
            if (!Thread.currentThread().isInterrupted) writer.error(id, e.message ?: e.toString())
          } finally {
            inFlight.remove(id)
          }
        }
      }
      else -> {
        // The rest of the message can't be parsed, so the protocol says to exit.
        System.err.println("Unknown message kind: $kind")
        exitProcess(1)
      }
    }
  }
}

private fun watchParent(pid: Long) {
  Thread(
      {
        while (true) {
          Thread.sleep(30_000)
          if (ProcessHandle.of(pid).map { it.isAlive }.orElse(false) != true) exitProcess(1)
        }
      },
      "parent-process-checker",
    )
    .apply { isDaemon = true }
    .start()
}

private fun licenseText(): String =
  listOf("/LICENSE", "/KTFMT_LICENSE")
    .mapNotNull { name ->
      object {}.javaClass.getResourceAsStream(name)?.use { it.readBytes().decodeToString() }
    }
    .joinToString("\n\n")
