package dev.sargunv.dprint.ktfmt

import java.util.Properties
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.kotlinx.ktfmt.format.FileType
import org.jetbrains.kotlinx.ktfmt.format.Formatter
import org.jetbrains.kotlinx.ktfmt.format.KotlinCode

val PLUGIN_VERSION: String =
  Properties()
    .apply { ResolvedConfig::class.java.getResourceAsStream("/plugin.properties")!!.use(::load) }
    .getProperty("version")

const val FILE_MATCHING_JSON = """{"fileExtensions":["kt","kts"],"fileNames":[]}"""

fun pluginInfoJson(): String = buildJsonObject {
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

/**
 * Returns the formatted text, or null when nothing changed. Throws ktfmt's ParseError on invalid
 * input.
 */
fun formatKotlin(path: String, text: String, config: ResolvedConfig): String? {
  val fileType = if (path.endsWith(".kts")) FileType.SCRIPT else FileType.REGULAR
  // ktfmt keeps the input's first line ending, which is what "auto" means.
  val formatted = Formatter.format(config.options, KotlinCode.from(text, fileType))
  val output =
    when (config.newLineKind) {
      NewLineKind.AUTO -> formatted
      NewLineKind.LF -> formatted.withLineEndings("\n")
      NewLineKind.CRLF -> formatted.withLineEndings("\r\n")
      NewLineKind.SYSTEM -> formatted.withLineEndings(System.lineSeparator())
    }
  return output.takeIf { it != text }
}

private fun String.withLineEndings(separator: String) = lineSequence().joinToString(separator)
