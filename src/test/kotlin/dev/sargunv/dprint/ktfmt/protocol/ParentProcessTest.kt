package dev.sargunv.dprint.ktfmt.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ParentProcessTest {
  @Test
  fun `parent pid argument`() {
    assertEquals(42L, parentProcessId(arrayOf("--parent-pid", "42", "--init")))
    assertNull(parentProcessId(arrayOf("--init")))
    assertNull(parentProcessId(arrayOf("--parent-pid")))
    assertNull(parentProcessId(arrayOf("--parent-pid", "nope")))
  }
}
