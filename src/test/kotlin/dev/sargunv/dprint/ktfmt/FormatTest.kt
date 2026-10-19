package dev.sargunv.dprint.ktfmt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.jetbrains.kotlinx.ktfmt.format.ParseError

class FormatTest {
  private fun config(plugin: String = "{}", global: String = "{}") = resolveConfig(global, plugin)

  @Test
  fun formatsRegularFile() {
    assertEquals("fun f() = 1\n", formatKotlin("A.kt", "fun  f( ) =1", config()))
  }

  @Test
  fun unchangedInputReturnsNull() {
    assertNull(formatKotlin("A.kt", "fun f() = 1\n", config()))
  }

  @Test
  fun scriptsAllowTopLevelStatements() {
    val script = "println( \"hi\" )\n"
    assertEquals("println(\"hi\")\n", formatKotlin("build.gradle.kts", script, config()))
    assertFailsWith<ParseError> { formatKotlin("Build.kt", script, config()) }
  }

  @Test
  fun parseErrorsThrow() {
    val error = assertFailsWith<ParseError> { formatKotlin("A.kt", "fun f( {", config()) }
    assert(error.message!!.isNotBlank())
  }

  @Test
  fun appliesOptions() {
    val code = "class A {\n  fun f() {\n    g(aaaa, bbbb)\n  }\n}\n"
    assertEquals(
      "class A {\n    fun f() {\n        g(\n          aaaa,\n          bbbb,\n        )\n    }\n}\n",
      formatKotlin(
        "A.kt",
        code,
        config("""{"lineWidth":15,"blockIndent":4,"continuationIndent":2}"""),
      ),
    )
  }

  @Test
  fun inheritsGlobalLineWidth() {
    val code = "val x = listOf(aaaa, bbbb)\n"
    assertNull(formatKotlin("A.kt", code, config()))
    assertEquals(
      "val x =\n    listOf(\n        aaaa,\n        bbbb,\n    )\n",
      formatKotlin("A.kt", code, config(global = """{"lineWidth":20}""")),
    )
  }

  @Test
  fun removeUnusedImports() {
    val code = "import a.B\n\nfun f() = 1\n"
    assertEquals("fun f() = 1\n", formatKotlin("A.kt", code, config()))
    assertNull(formatKotlin("A.kt", code, config("""{"removeUnusedImports":false}""")))
  }

  @Test
  fun newLineKindAutoKeepsInputLineEndings() {
    assertNull(formatKotlin("A.kt", "fun f() = 1\r\n\r\nfun g() = 2\r\n", config()))
    assertEquals(
      "fun f() = 1\r\n\r\nfun g() = 2\r\n",
      formatKotlin("A.kt", "fun f()=1\r\nfun g()=2\r\n", config()),
    )
  }

  @Test
  fun newLineKindForcesLineEndings() {
    val crlf = config("""{"newLineKind":"crlf"}""")
    assertEquals("fun f() = 1\r\n", formatKotlin("A.kt", "fun f() = 1\n", crlf))
    assertNull(formatKotlin("A.kt", "fun f() = 1\r\n", crlf))
    val lf = config(global = """{"newLineKind":"lf"}""")
    assertEquals("fun f() = 1\n", formatKotlin("A.kt", "fun f() = 1\r\n", lf))
  }
}
