package dev.sargunv.dprint.ktfmt

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.jetbrains.kotlinx.ktfmt.format.TrailingCommaManagementStrategy

class ConfigTest {
  private fun resolved(global: String = "{}", plugin: String = "{}"): JsonObject =
    Json.parseToJsonElement(resolveConfig(global, plugin).resolvedJson()).jsonObject

  private fun expected(json: String) = Json.parseToJsonElement(json).jsonObject

  @Test
  fun defaultsToMetaStyle() {
    val config = resolveConfig("{}", "{}")
    assertEquals(emptyList(), config.diagnostics)
    assertEquals("[]", config.diagnosticsJson())
    assertEquals(
      expected(
        """
        |{"style":"meta","lineWidth":100,"blockIndent":2,"continuationIndent":4,
        |"trailingCommas":"onlyAdd","removeUnusedImports":true,"preserveLambdaBreaks":true,
        |"newLineKind":"auto"}
        """
          .trimMargin(),
      ),
      resolved(),
    )
  }

  @Test
  fun styleDefaults() {
    val google = resolveConfig("{}", """{"style":"google"}""").options
    assertEquals(2, google.blockIndent)
    assertEquals(2, google.continuationIndent)
    assertEquals(TrailingCommaManagementStrategy.COMPLETE, google.trailingCommaManagementStrategy)

    val kotlinlang = resolveConfig("{}", """{"style":"kotlinlang"}""").options
    assertEquals(4, kotlinlang.blockIndent)
    assertEquals(4, kotlinlang.continuationIndent)
    assertEquals(100, kotlinlang.maxWidth)
  }

  @Test
  fun inheritsGlobalConfig() {
    val global = """{"lineWidth":80,"indentWidth":8,"useTabs":true,"newLineKind":"crlf"}"""
    val config = resolveConfig(global, "{}")
    assertEquals(emptyList(), config.diagnostics)
    assertEquals(80, config.options.maxWidth)
    // Indentation comes from the style; global indentWidth and useTabs don't apply to ktfmt.
    assertEquals(2, config.options.blockIndent)
    assertEquals(4, config.options.continuationIndent)
    assertEquals(NewLineKind.CRLF, config.newLineKind)
  }

  @Test
  fun pluginConfigWinsOverGlobal() {
    val config =
      resolveConfig(
        """{"lineWidth":80,"newLineKind":"crlf"}""",
        """{"lineWidth":120,"newLineKind":"lf"}""",
      )
    assertEquals(120, config.options.maxWidth)
    assertEquals(NewLineKind.LF, config.newLineKind)
  }

  @Test
  fun allKeys() {
    val plugin =
      """
      |{"style":"google","lineWidth":90,"blockIndent":3,"continuationIndent":6,
      |"trailingCommas":"none","removeUnusedImports":false,"preserveLambdaBreaks":false,
      |"newLineKind":"system","useTabs":false}
      """
        .trimMargin()
    val config = resolveConfig("{}", plugin)
    assertEquals(emptyList(), config.diagnostics)
    assertEquals(expected(plugin).minus("useTabs"), resolved(plugin = plugin))
  }

  @Test
  fun withOverrides() {
    val config = resolveConfig("""{"lineWidth":80}""", """{"style":"google","blockIndent":4}""")
    val overridden = config.withOverrides("""{"lineWidth":60,"blockIndent":2}""")
    assertEquals("google", overridden.style)
    assertEquals(60, overridden.options.maxWidth)
    assertEquals(2, overridden.options.blockIndent)
    assertEquals(80, config.withOverrides("{}").options.maxWidth)
  }

  @Test
  fun diagnostics() {
    val config =
      resolveConfig(
        "{}",
        """
        |{"style":"facebook","lineWidth":"wide","blockIndent":-1,"continuationIndent":2.5,
        |"trailingCommas":"always","removeUnusedImports":"yes","preserveLambdaBreaks":1,
        |"newLineKind":"cr","useTabs":true,"indentWidth":4,"maxWidth":100}
        """
          .trimMargin(),
      )
    assertEquals(
      listOf(
        "style" to "Expected one of meta, google, kotlinlang, got \"facebook\"",
        "lineWidth" to "Expected a positive integer, got \"wide\"",
        "blockIndent" to "Expected a non-negative integer, got -1",
        "continuationIndent" to "Expected a non-negative integer, got 2.5",
        "trailingCommas" to "Expected one of none, onlyAdd, complete, got \"always\"",
        "removeUnusedImports" to "Expected a boolean, got \"yes\"",
        "preserveLambdaBreaks" to "Expected a boolean, got 1",
        "newLineKind" to "Expected one of auto, lf, crlf, system, got \"cr\"",
        "useTabs" to "ktfmt does not support indenting with tabs",
        "indentWidth" to "Unknown property; use blockIndent and continuationIndent",
        "maxWidth" to "Unknown property in configuration",
      ),
      config.diagnostics,
    )
    // Invalid values fall back to the defaults.
    assertEquals(resolved(), resolved(plugin = """{"style":"facebook","lineWidth":"wide"}"""))
    assertEquals(
      expected("""{"propertyName":"maxWidth","message":"Unknown property in configuration"}"""),
      Json.parseToJsonElement(config.diagnosticsJson()).jsonArray.last(),
    )
  }

  @Test
  fun schemaCoversResolvedConfig() {
    val schema = Json.parseToJsonElement(File("schema.json").readText()).jsonObject
    assertEquals(
      resolved().keys + "useTabs",
      schema.getValue("properties").jsonObject.keys,
    )
  }

  @Test
  fun pluginInfo() {
    val info = Json.parseToJsonElement(pluginInfoJson()).jsonObject
    assertTrue(PLUGIN_VERSION.matches(Regex("""\d+\.\d+\.\d+""")), PLUGIN_VERSION)
    val v = PLUGIN_VERSION
    assertEquals(
      expected(
        """{"name":"dprint-plugin-ktfmt","version":"$v","configKey":"ktfmt",
          |"helpUrl":"https://github.com/sargunv/dprint-plugin-ktfmt",
          |"configSchemaUrl":"https://plugins.dprint.dev/sargunv/dprint-plugin-ktfmt/$v/schema.json",
          |"updateUrl":"https://plugins.dprint.dev/sargunv/dprint-plugin-ktfmt/latest.json"}"""
          .trimMargin(),
      ),
      info,
    )
  }
}
