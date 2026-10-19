package dev.sargunv.dprint.ktfmt

import dev.sargunv.dprint.ktfmt.isolates.IsolatePool
import dev.sargunv.dprint.ktfmt.protocol.PluginHandler
import java.util.Properties
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

val PLUGIN_VERSION: String =
  Properties()
    .apply { KtfmtHandler::class.java.getResourceAsStream("/plugin.properties")!!.use(::load) }
    .getProperty("version")

internal object KtfmtHandler : PluginHandler<ResolvedConfig> {
  // In a native image, formats spread across up to 8 isolates so that one heap's stop-the-world
  // collection doesn't pause the rest. Lazy because this object is initialized at image build time.
  private val isolates by lazy {
    if (System.getProperty("org.graalvm.nativeimage.imagecode") == "runtime") {
      IsolatePool(Runtime.getRuntime().availableProcessors().coerceAtMost(8))
    } else {
      null
    }
  }

  override fun pluginInfo() = buildJsonObject {
    put("name", "dprint-plugin-ktfmt")
    put("version", PLUGIN_VERSION)
    put("configKey", "ktfmt")
    put("helpUrl", "https://github.com/sargunv/dprint-plugin-ktfmt")
    put(
      "configSchemaUrl",
      "https://plugins.dprint.dev/sargunv/dprint-plugin-ktfmt/$PLUGIN_VERSION/schema.json",
    )
    put("updateUrl", "https://plugins.dprint.dev/sargunv/dprint-plugin-ktfmt/latest.json")
  }
    .toString()

  override fun license() =
    listOf("/LICENSE", "/KTFMT_LICENSE").joinToString("\n\n") { name ->
      javaClass.getResourceAsStream(name)!!.use { it.readBytes().decodeToString() }
    }

  override fun registerConfig(globalJson: String, pluginJson: String) =
    resolveConfig(globalJson, pluginJson)

  override fun diagnostics(config: ResolvedConfig) = config.diagnosticsJson()

  override fun fileMatching(config: ResolvedConfig) =
    """{"fileExtensions":["kt","kts"],"fileNames":[]}"""

  override fun resolvedConfig(config: ResolvedConfig) = config.resolvedJson()

  override fun applyOverrides(config: ResolvedConfig, overrideJson: String) =
    config.withOverrides(overrideJson)

  // The JVM runs the worker path in-process, so its tests cover the config round trip too.
  override fun format(path: String, text: ByteArray, config: ResolvedConfig): ByteArray? {
    val pool = isolates
    return if (pool != null) pool.format(path, config.workerJson, text)
    else formatInWorker(path, config.workerJson, text)
  }
}
