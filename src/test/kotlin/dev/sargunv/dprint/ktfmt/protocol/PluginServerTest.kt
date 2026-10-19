package dev.sargunv.dprint.ktfmt.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

data class FakeConfig(val plugin: String, val override: String? = null)

/** Uppercases text; `error.kt` throws, and gated paths block until released. */
class FakeHandler : PluginHandler<FakeConfig> {
  val formatted = ConcurrentLinkedQueue<String>()
  private val gates = ConcurrentHashMap<String, Pair<CountDownLatch, CountDownLatch>>()

  /** Makes formats of [path] block; returns (started, release) latches. */
  fun gate(path: String) = (CountDownLatch(1) to CountDownLatch(1)).also { gates[path] = it }

  override fun pluginInfo() = """{"name":"fake"}"""

  override fun license() = "fake license"

  override fun registerConfig(globalJson: String, pluginJson: String): FakeConfig {
    require(pluginJson != "invalid") { "Invalid config" }
    return FakeConfig(pluginJson)
  }

  override fun diagnostics(config: FakeConfig) = "diagnostics ${config.plugin}"

  override fun fileMatching(config: FakeConfig) = "matching ${config.plugin}"

  override fun resolvedConfig(config: FakeConfig) = "resolved ${config.plugin}"

  override fun applyOverrides(config: FakeConfig, overrideJson: String) =
    config.copy(override = overrideJson)

  override fun format(path: String, text: ByteArray, config: FakeConfig): ByteArray? {
    formatted += path
    gates[path]?.let { (started, release) ->
      started.countDown()
      release.await()
    }
    check(path != "error.kt") { "Parse error at 1:1" }
    val input = text.decodeToString()
    val output = input.uppercase() + (config.override ?: "")
    return output.takeIf { it != input }?.encodeToByteArray()
  }
}

class PluginServerTest {
  private val handler = FakeHandler()
  private val clients = mutableListOf<TestClient<*>>()

  private fun connect(parallelism: Int = 4) =
    TestClient(handler, parallelism).also {
      clients += it
      it.handshake()
    }

  @AfterTest fun closeClients() = clients.forEach { it.close() }

  @Test
  fun `handshake and is-alive match recorded bytes`() {
    val request =
      bytes(
        "00000000", // schema version request
        "00000007 00000004 ffffffff", // id 7, IsAlive
      )
    val output = ByteArrayOutputStream()
    assertEquals(0, PluginServer(ByteArrayInputStream(request), output, handler).run())
    assertContentEquals(
      bytes(
        "00000000 00000005", // success, schema version 5
        "00000000 00000000 00000007 ffffffff", // id 0, Success for message 7
      ),
      output.toByteArray(),
    )
  }

  @Test
  fun `format response matches recorded bytes`() {
    // Formats answer asynchronously, so drive this one over a live connection.
    val client = TestClient(handler).also { clients += it }
    client.output.write(
      bytes(
        "00000000",
        "00000001 00000007 00000001 00000002 7b7d 00000002 7b7d ffffffff", // RegisterConfig 1
        // Format "a.kt", range 0..1, config 1, no override, text "a"
        "00000002 0000000d 00000004 612e6b74 00000000 00000001 00000001 00000000 00000001 61 ffffffff",
      ),
    )
    client.output.flush()
    val expected =
      bytes(
        "00000000 00000005",
        "00000000 00000000 00000001 ffffffff", // Success for 1
        "00000001 0000000e 00000002 00000001 00000001 41 ffffffff", // FormatResponse for 2: "A"
      )
    assertContentEquals(expected, client.input.readNBytes(expected.size))
  }

  @Test
  fun `rejects a bad schema request`() {
    val output = ByteArrayOutputStream()
    val exitCode = PluginServer(ByteArrayInputStream(bytes("00000005")), output, handler).run()
    assertEquals(1, exitCode)
    assertEquals(0, output.size())
  }

  @Test
  fun `plugin info, license, and config updates`() {
    val client = connect()
    assertEquals(Response(0, Kind.DATA, 1, """{"name":"fake"}"""), client.request(Kind.PLUGIN_INFO))
    assertEquals("fake license", client.request(Kind.LICENSE).body)
    val updates = client.request(Kind.CHECK_CONFIG_UPDATES) { sized("""{"config":{}}""") }
    assertEquals(Kind.DATA, updates.kind)
    assertEquals("""{"changes":[]}""", updates.body)
  }

  @Test
  fun `register config and query it`() {
    val client = connect()
    assertEquals(Kind.SUCCESS, client.registerConfig(1, plugin = "one").kind)
    assertEquals(Kind.SUCCESS, client.registerConfig(2, plugin = "two").kind)
    assertEquals(
      Response(2, Kind.DATA, 3, "diagnostics one"),
      client.request(Kind.CONFIG_DIAGNOSTICS) { writeInt(1) },
    )
    assertEquals("matching two", client.request(Kind.FILE_MATCHING) { writeInt(2) }.body)
    assertEquals("resolved one", client.request(Kind.RESOLVED_CONFIG) { writeInt(1) }.body)
  }

  @Test
  fun `invalid config is an error`() {
    val client = connect()
    assertEquals(
      Response(0, Kind.ERROR, 1, "Invalid config"),
      client.registerConfig(1, plugin = "invalid"),
    )
    assertEquals(Kind.ERROR, client.request(Kind.CONFIG_DIAGNOSTICS) { writeInt(1) }.kind)
  }

  @Test
  fun `unknown config ids are errors`() {
    val client = connect()
    for (kind in listOf(Kind.CONFIG_DIAGNOSTICS, Kind.FILE_MATCHING, Kind.RESOLVED_CONFIG)) {
      val response = client.request(kind) { writeInt(9) }
      assertEquals(Kind.ERROR, response.kind)
      assertEquals("Did not find configuration for id: 9", response.body)
    }
    assertEquals(Kind.ERROR, client.format("a.kt", "a", configId = 9).kind)
    assertTrue(handler.formatted.isEmpty())
  }

  @Test
  fun `release config`() {
    val client = connect()
    client.registerConfig(1)
    client.registerConfig(2)
    assertEquals(Kind.SUCCESS, client.request(Kind.RELEASE_CONFIG) { writeInt(1) }.kind)
    assertEquals(Kind.ERROR, client.request(Kind.RESOLVED_CONFIG) { writeInt(1) }.kind)
    assertEquals(Kind.ERROR, client.format("a.kt", "a", configId = 1).kind)
    assertEquals(Kind.DATA, client.request(Kind.RESOLVED_CONFIG) { writeInt(2) }.kind)
  }

  @Test
  fun `format change, no change, error, override, and range`() {
    val client = connect()
    client.registerConfig(1)
    assertEquals(Response(1, Kind.FORMAT_RESPONSE, 2, "ABC"), client.format("a.kt", "abc"))
    assertEquals(Response(2, Kind.FORMAT_RESPONSE, 3, null), client.format("a.kt", "ABC"))
    assertEquals(Response(3, Kind.ERROR, 4, "Parse error at 1:1"), client.format("error.kt", "x"))
    assertEquals("ABC!", client.format("a.kt", "ABC", override = "!").body)
    assertNull(client.format("a.kt", "ABC", override = "{}").body)
    // Range formats aren't supported yet and answer "no change" without formatting.
    val range = client.format("range.kt", "abc", range = 1 to 2)
    assertEquals(Kind.FORMAT_RESPONSE, range.kind)
    assertNull(range.body)
    assertFalse("range.kt" in handler.formatted)
  }

  @Test
  fun `formats run concurrently`() {
    val client = connect(parallelism = 2)
    client.registerConfig(1)
    val (started, release) = handler.gate("slow.kt")
    val slow = client.sendFormat("slow.kt", "slow")
    assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    assertEquals("FAST", client.format("fast.kt", "fast").body)
    release.countDown()
    assertEquals(Response(2, Kind.FORMAT_RESPONSE, slow, "SLOW"), client.receive())
  }

  @Test
  fun `cancel drops running and queued formats`() {
    val client = connect(parallelism = 1)
    client.registerConfig(1)
    val (started, release) = handler.gate("running.kt")
    val running = client.sendFormat("running.kt", "a")
    val queued = client.sendFormat("queued.kt", "b")
    assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    client.send(Kind.CANCEL_FORMAT) { writeInt(running) }
    client.send(Kind.CANCEL_FORMAT) { writeInt(queued) }
    client.send(Kind.CANCEL_FORMAT) { writeInt(12345) } // unknown ids are ignored
    client.roundTrip()
    release.countDown()
    // The single format thread runs in order, so this answer comes after the cancelled ones.
    assertEquals("C", client.format("after.kt", "c").body)
    assertEquals(listOf("running.kt", "after.kt"), handler.formatted.toList())
  }

  @Test
  fun `messages that need no answer are consumed`() {
    val client = connect()
    client.send(Kind.SUCCESS) { writeInt(1) }
    client.send(Kind.DATA) {
      writeInt(1)
      sized("data")
    }
    client.send(Kind.ERROR) {
      writeInt(1)
      sized("error from the CLI")
    }
    client.send(Kind.FORMAT_RESPONSE) {
      writeInt(1)
      writeInt(0)
    }
    client.send(Kind.FORMAT_RESPONSE) {
      writeInt(1)
      writeInt(1)
      sized("text")
    }
    client.roundTrip()
  }

  @Test
  fun `host format requests are refused`() {
    val client = connect()
    val response =
      client.request(Kind.HOST_FORMAT) {
        writeInt(1)
        sized("a.kt")
        writeInt(0)
        writeInt(1)
        sized("{}")
        sized("a")
      }
    assertEquals(Kind.ERROR, response.kind)
    client.roundTrip()
  }

  @Test
  fun `close acknowledges and stops`() {
    val client = connect()
    val close = client.send(Kind.CLOSE)
    assertEquals(Response(0, Kind.SUCCESS, close), client.receive())
    assertEquals(0, client.exitCode())
  }

  @Test
  fun `end of input stops cleanly`() {
    val client = connect()
    client.roundTrip()
    client.output.close()
    assertEquals(0, client.exitCode())
  }

  @Test
  fun `unknown message kind exits`() {
    val client = connect()
    client.send(99)
    assertEquals(1, client.exitCode())
  }

  @Test
  fun `missing success bytes exit`() {
    val client = connect()
    client.output.writeInt(1)
    client.output.writeInt(Kind.IS_ALIVE)
    client.output.writeInt(0)
    client.output.flush()
    assertEquals(1, client.exitCode())
  }
}

private fun bytes(vararg hex: String): ByteArray =
  hex.joinToString("").replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
