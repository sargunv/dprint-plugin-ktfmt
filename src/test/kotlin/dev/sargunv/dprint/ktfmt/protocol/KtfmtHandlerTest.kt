package dev.sargunv.dprint.ktfmt.protocol

import dev.sargunv.dprint.ktfmt.KtfmtHandler
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The protocol server wired to the real ktfmt adapter, as `main` runs it. */
class KtfmtHandlerTest {
  private val client = TestClient(KtfmtHandler).also { it.handshake() }

  @AfterTest fun close() = client.close()

  @Test
  fun `plugin info and license`() {
    assertContains(client.request(Kind.PLUGIN_INFO).body!!, "\"configKey\":\"ktfmt\"")
    assertContains(client.request(Kind.LICENSE).body!!, "Apache License")
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
