package dev.sargunv.dprint.ktfmt

import dev.sargunv.dprint.ktfmt.isolates.IsolatePool
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
  // Native image: format threads share up to 8 isolates, created as threads first need them. On
  // okhttp (32 cores) 8 isolates format about as fast as 32 at under half the memory (1.1 vs 2.3 GB
  // peak), and 3x faster than one. JVM: plain threads. Lazy because this object is initialized at
  // image build time.
  private val isolates by lazy {
    if (System.getProperty("org.graalvm.nativeimage.imagecode") == "runtime") {
      IsolatePool(Runtime.getRuntime().availableProcessors().coerceAtMost(8))
    } else {
      null
    }
  }

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

  // The JVM path goes through the same JSON round trip as the isolates, so tests cover it.
  override fun format(path: String, text: ByteArray, config: ResolvedConfig): ByteArray? {
    val pool = isolates
    return if (pool != null) pool.format(path, config.workerJson, text)
    else formatInWorker(path, config.workerJson, text)
  }
}
