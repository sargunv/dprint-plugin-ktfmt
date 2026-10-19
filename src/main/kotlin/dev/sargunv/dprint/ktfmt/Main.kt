package dev.sargunv.dprint.ktfmt

import dev.sargunv.dprint.ktfmt.protocol.PluginHandler
import dev.sargunv.dprint.ktfmt.protocol.PluginServer
import dev.sargunv.dprint.ktfmt.protocol.parentProcessId
import dev.sargunv.dprint.ktfmt.protocol.startParentProcessChecker
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.PrintStream
import kotlin.system.exitProcess

fun main(args: Array<String>) {
  // Take the real stdout for the protocol before anything else can touch it, then point
  // System.out at stderr so stray library or JVM output becomes a dprint warning instead of
  // corrupting the message stream (see sargunv/stringline#1348 for the exec-plugin version of
  // this).
  val protocolOut = FileOutputStream(FileDescriptor.out)
  System.setOut(PrintStream(FileOutputStream(FileDescriptor.err), true))

  parentProcessId(args)?.let(::startParentProcessChecker)

  val server = PluginServer(FileInputStream(FileDescriptor.`in`), protocolOut, KtfmtHandler)
  exitProcess(server.run())
}

internal object KtfmtHandler : PluginHandler<ResolvedConfig> {
  override fun pluginInfo() = pluginInfoJson()

  override fun license() =
    listOf("/LICENSE", "/KTFMT_LICENSE")
      .mapNotNull { name ->
        javaClass.getResourceAsStream(name)?.use { it.readBytes().decodeToString() }
      }
      .joinToString("\n\n")

  override fun registerConfig(globalJson: String, pluginJson: String) =
    resolveConfig(globalJson, pluginJson)

  override fun diagnostics(config: ResolvedConfig) = config.diagnosticsJson()

  override fun fileMatching(config: ResolvedConfig) = FILE_MATCHING_JSON

  override fun resolvedConfig(config: ResolvedConfig) = config.resolvedJson()

  override fun applyOverrides(config: ResolvedConfig, overrideJson: String) =
    config.withOverrides(overrideJson)

  override fun format(path: String, text: ByteArray, config: ResolvedConfig) =
    formatKotlin(path, text.decodeToString(), config)?.encodeToByteArray()
}
