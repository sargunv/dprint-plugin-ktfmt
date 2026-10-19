package dev.sargunv.dprint.ktfmt.protocol

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
