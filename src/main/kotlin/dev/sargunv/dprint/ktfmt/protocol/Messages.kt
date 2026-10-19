package dev.sargunv.dprint.ktfmt.protocol

import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

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

internal class ProtocolException(message: String) : IOException(message)

internal fun DataInputStream.sized(): ByteArray = ByteArray(readInt()).also(::readFully)

/** Serializes whole messages onto the output, which carries nothing but the protocol. */
internal class MessageWriter(stream: OutputStream) {
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
