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
import org.jetbrains.kotlinx.ktfmt.format.Formatter
import org.jetbrains.kotlinx.ktfmt.format.FormattingOptions
import org.jetbrains.kotlinx.ktfmt.format.TrailingCommaManagementStrategy

private val STYLES =
  mapOf(
    "meta" to Formatter.META_FORMAT,
    "google" to Formatter.GOOGLE_FORMAT,
    "kotlinlang" to Formatter.KOTLINLANG_FORMAT,
  )

private val TRAILING_COMMAS =
  mapOf(
    "none" to TrailingCommaManagementStrategy.NONE,
    "onlyAdd" to TrailingCommaManagementStrategy.ONLY_ADD,
    "complete" to TrailingCommaManagementStrategy.COMPLETE,
  )

enum class NewLineKind(val key: String) {
  AUTO("auto"),
  LF("lf"),
  CRLF("crlf"),
  SYSTEM("system"),
}

class ResolvedConfig(
  val style: String,
  val options: FormattingOptions,
  val newLineKind: NewLineKind,
  val diagnostics: List<Pair<String, String>>,
  private val raw: JsonObject,
  private val global: JsonObject,
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
    put("newLineKind", newLineKind.key)
  }
    .toString()

  /** The config as [IsolatePool]'s workers receive it. */
  val workerJson: String by lazy { resolvedJson() }

  fun withOverrides(overrideJson: String): ResolvedConfig =
    resolve(global, JsonObject(raw + Json.parseToJsonElement(overrideJson).jsonObject))
}

fun resolveConfig(globalJson: String, pluginJson: String): ResolvedConfig =
  resolve(
    Json.parseToJsonElement(globalJson).jsonObject,
    Json.parseToJsonElement(pluginJson).jsonObject,
  )

/**
 * Resolves the plugin's config. Plugin keys win over dprint's global ones, which win over the
 * style's defaults. Invalid values fall back as if unset and are reported as diagnostics.
 */
fun resolve(global: JsonObject, raw: JsonObject): ResolvedConfig {
  val diagnostics = mutableListOf<Pair<String, String>>()
  val props = raw.toMutableMap()

  fun <T> take(name: String, expected: String, parse: (JsonElement) -> T?): T? =
    props.remove(name)?.let { value ->
      parse(value) ?: null.also { diagnostics += name to "Expected $expected, got $value" }
    }

  fun JsonElement.str() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

  fun JsonElement.int(min: Int) =
    (this as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it >= min }

  fun JsonElement.bool() = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

  fun JsonElement.newLineKind() = str()?.let { s -> NewLineKind.entries.find { it.key == s } }

  val newLineKinds = NewLineKind.entries.joinToString { it.key }

  val style =
    take("style", "one of ${STYLES.keys.joinToString()}") { v -> v.str()?.takeIf { it in STYLES } }
      ?: "meta"
  val base = STYLES.getValue(style)
  val lineWidth =
    take("lineWidth", "a positive integer") { it.int(1) }
      ?: global["lineWidth"]?.int(1)
      ?: base.maxWidth
  val blockIndent = take("blockIndent", "a non-negative integer") { it.int(0) } ?: base.blockIndent
  val continuationIndent =
    take("continuationIndent", "a non-negative integer") { it.int(0) } ?: base.continuationIndent
  val trailingCommas =
    take("trailingCommas", "one of ${TRAILING_COMMAS.keys.joinToString()}") { v ->
      v.str()?.let(TRAILING_COMMAS::get)
    } ?: base.trailingCommaManagementStrategy
  val removeUnusedImports =
    take("removeUnusedImports", "a boolean") { it.bool() } ?: base.removeUnusedImports
  val preserveLambdaBreaks =
    take("preserveLambdaBreaks", "a boolean") { it.bool() } ?: base.preserveLambdaBreaks
  val newLineKind =
    take("newLineKind", "one of $newLineKinds") { it.newLineKind() }
      ?: global["newLineKind"]?.newLineKind()
      ?: NewLineKind.AUTO
  // ktfmt only indents with spaces. A global useTabs is ignored, but asking for tabs here is an
  // error worth surfacing.
  if (take("useTabs", "a boolean") { it.bool() } == true) {
    diagnostics += "useTabs" to "ktfmt does not support indenting with tabs"
  }
  props.remove("indentWidth")?.let {
    diagnostics += "indentWidth" to "Unknown property; use blockIndent and continuationIndent"
  }
  props.keys.forEach { diagnostics += it to "Unknown property in configuration" }

  val options =
    base
      .toBuilder()
      .maxWidth(lineWidth)
      .blockIndent(blockIndent)
      .continuationIndent(continuationIndent)
      .trailingCommaManagementStrategy(trailingCommas)
      .removeUnusedImports(removeUnusedImports)
      .preserveLambdaBreaks(preserveLambdaBreaks)
      .build()
  return ResolvedConfig(style, options, newLineKind, diagnostics, raw, global)
}
