package dev.sargunv.dprint.ktfmt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Compares against the ktfmt CLI's output; see src/test/resources/parity/README.md. */
class ParityTest {
  private val fixtures =
    listOf(
      "FakeProxySelector.kt",
      "ForwardingResponseBody.kt",
      "Messy.kt",
      "RecordingCookieJar.kt",
      "build.gradle.kts",
    )

  private fun read(dir: String, name: String) =
    javaClass.getResource("/parity/$dir/$name.txt")!!.readText()

  private fun check(style: String) {
    val config = resolveConfig("{}", """{"style":"$style"}""")
    for (name in fixtures) {
      val expected = read(style, name)
      assertEquals(
        expected,
        formatKotlin(name, read("input", name), config) ?: read("input", name),
        "$style/$name",
      )
      assertNull(formatKotlin(name, expected, config), "$style/$name is stable")
    }
  }

  @Test fun meta() = check("meta")

  @Test fun google() = check("google")

  @Test fun kotlinlang() = check("kotlinlang")
}
