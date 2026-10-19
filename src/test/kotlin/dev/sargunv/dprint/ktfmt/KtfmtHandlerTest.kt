package dev.sargunv.dprint.ktfmt

import dev.sargunv.dprint.ktfmt.protocol.Kind
import dev.sargunv.dprint.ktfmt.protocol.TestClient
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** The protocol server wired to the real ktfmt adapter, as `main` runs it. */
class KtfmtHandlerTest {
  private val client = TestClient(KtfmtHandler).also { it.handshake() }

  @AfterTest fun close() = client.close()

  @Test
  fun `plugin info`() {
    assertTrue(PLUGIN_VERSION.matches(Regex("""\d+\.\d+\.\d+""")), PLUGIN_VERSION)
    val v = PLUGIN_VERSION
    assertEquals(
      Json.parseToJsonElement(
          """{"name":"dprint-plugin-ktfmt","version":"$v","configKey":"ktfmt",
            |"helpUrl":"https://github.com/sargunv/dprint-plugin-ktfmt",
            |"configSchemaUrl":"https://plugins.dprint.dev/sargunv/dprint-plugin-ktfmt/$v/schema.json",
            |"updateUrl":"https://plugins.dprint.dev/sargunv/dprint-plugin-ktfmt/latest.json"}"""
            .trimMargin(),
        )
        .jsonObject,
      Json.parseToJsonElement(client.request(Kind.PLUGIN_INFO).body!!).jsonObject,
    )
  }

  @Test
  fun license() {
    val license = client.request(Kind.LICENSE).body!!
    assertTrue(license.startsWith("MIT OR Apache-2.0"))
    assertContains(license, "Apache License")
  }

  @Test
  fun `diagnostics and file matching`() {
    client.registerConfig(1, plugin = """{"notAThing":true}""")
    assertContains(client.request(Kind.CONFIG_DIAGNOSTICS) { writeInt(1) }.body!!, "notAThing")
    assertContains(client.request(Kind.FILE_MATCHING) { writeInt(1) }.body!!, "\"kt\"")
  }

  @Test
  fun `formats kotlin`() {
    client.registerConfig(1)
    assertEquals("fun f() {}\n", client.format("a.kt", "fun  f( ) {\n}").body)
    assertNull(client.format("a.kt", "fun f() {}\n").body)
    assertEquals("println(1)\n", client.format("a.kts", "println( 1 )").body)
    val error = client.format("a.kt", "fun (")
    assertEquals(Kind.ERROR, error.kind)
    assertTrue(error.body!!.isNotBlank())
  }

  @Test
  fun `applies override config`() {
    client.registerConfig(1)
    val text = "fun f() {\n  g()\n}\n"
    assertNull(client.format("a.kt", text).body)
    assertEquals(
      "fun f() {\n    g()\n}\n",
      client.format("a.kt", text, override = """{"blockIndent":4}""").body,
    )
  }
}
