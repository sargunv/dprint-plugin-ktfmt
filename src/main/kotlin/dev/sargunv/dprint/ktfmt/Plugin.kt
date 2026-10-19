package dev.sargunv.dprint.ktfmt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.jetbrains.kotlinx.ktfmt.format.FileType
import org.jetbrains.kotlinx.ktfmt.format.Formatter
import org.jetbrains.kotlinx.ktfmt.format.FormattingOptions
import org.jetbrains.kotlinx.ktfmt.format.KotlinCode
import org.jetbrains.kotlinx.ktfmt.format.TrailingCommaManagementStrategy

const val PLUGIN_VERSION = "0.0.1"

const val FILE_MATCHING_JSON = """{"fileExtensions":["kt","kts"],"fileNames":[]}"""

fun pluginInfoJson(): String = buildJsonObject {
  put("name", "dprint-plugin-ktfmt")
  put("version", PLUGIN_VERSION)
  put("configKey", "ktfmt")
  put("helpUrl", "https://github.com/sargunv/dprint-plugin-ktfmt")
  put("configSchemaUrl", "")
}
  .toString()

private val STYLES =
  mapOf(
    "meta" to Formatter.META_FORMAT,
    "google" to Formatter.GOOGLE_FORMAT,
    "kotlinlang" to Formatter.KOTLINLANG_FORMAT,
  )

private val TRAILING_COMMAS =
  mapOf(
    "complete" to TrailingCommaManagementStrategy.COMPLETE,
    "onlyAdd" to TrailingCommaManagementStrategy.ONLY_ADD,
    "none" to TrailingCommaManagementStrategy.NONE,
  )

class ResolvedConfig(
  val style: String,
  val options: FormattingOptions,
  private val raw: JsonObject,
  private val global: JsonObject,
  val diagnostics: List<Pair<String, String>>,
) {
  fun diagnosticsJson(): String = buildJsonArray {
    diagnostics.forEach { (property, message) ->
      add(
        buildJsonObject {
          put("propertyName", property)
          put("message", message)
        },
      )
    }
  }
    .toString()

  fun resolvedJson(): String = buildJsonObject {
    put("style", style)
    put("lineWidth", options.maxWidth)
    put("blockIndent", options.blockIndent)
    put("continuationIndent", options.continuationIndent)
    put(
      "trailingCommas",
      TRAILING_COMMAS.entries.first { it.value == options.trailingCommaManagementStrategy }.key,
    )
    put("removeUnusedImports", options.removeUnusedImports)
    put("preserveLambdaBreaks", options.preserveLambdaBreaks)
  }
    .toString()

  fun withOverrides(overrideJson: String): ResolvedConfig =
    resolve(global, JsonObject(raw + Json.parseToJsonElement(overrideJson).jsonObject))
}

fun resolveConfig(globalJson: String, pluginJson: String): ResolvedConfig =
  resolve(
    Json.parseToJsonElement(globalJson).jsonObject,
    Json.parseToJsonElement(pluginJson).jsonObject,
  )

private fun resolve(global: JsonObject, raw: JsonObject): ResolvedConfig {
  val diagnostics = mutableListOf<Pair<String, String>>()
  val props = raw.toMutableMap()

  fun <T> take(name: String, parse: (JsonElement) -> T?): T? =
    props.remove(name)?.let { value ->
      parse(value) ?: null.also { diagnostics += name to "Invalid value: $value" }
    }

  fun JsonElement.str() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
  fun JsonElement.int() = (this as? JsonPrimitive)?.intOrNull
  fun JsonElement.bool() = (this as? JsonPrimitive)?.booleanOrNull

  val style = take("style") { v -> v.str()?.takeIf { it in STYLES } } ?: "meta"
  val base = STYLES.getValue(style)
  val lineWidth = take("lineWidth") { it.int() } ?: global["lineWidth"]?.int() ?: base.maxWidth
  val blockIndent = take("blockIndent") { it.int() } ?: base.blockIndent
  val continuationIndent = take("continuationIndent") { it.int() } ?: base.continuationIndent
  val trailingCommas =
    take("trailingCommas") { v -> v.str()?.let(TRAILING_COMMAS::get) }
      ?: base.trailingCommaManagementStrategy
  val removeUnusedImports = take("removeUnusedImports") { it.bool() } ?: base.removeUnusedImports
  val preserveLambdaBreaks = take("preserveLambdaBreaks") { it.bool() } ?: base.preserveLambdaBreaks
  props.keys.forEach { diagnostics += it to "Unknown property in configuration" }

  val options =
    base.copy(
      maxWidth = lineWidth,
      blockIndent = blockIndent,
      continuationIndent = continuationIndent,
      trailingCommaManagementStrategy = trailingCommas,
      removeUnusedImports = removeUnusedImports,
      preserveLambdaBreaks = preserveLambdaBreaks,
    )
  return ResolvedConfig(style, options, raw, global, diagnostics)
}

/** Returns the formatted text, or null when ktfmt didn't change anything. */
fun formatKotlin(path: String, text: String, config: ResolvedConfig): String? {
  val fileType = if (path.endsWith(".kts")) FileType.SCRIPT else FileType.REGULAR
  val formatted = Formatter.format(config.options, KotlinCode.from(text, fileType))
  return formatted.takeIf { it != text }
}
