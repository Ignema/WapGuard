package app.wapguard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val MINUTE = 60_000L
private const val NOW = 1_762_000_000_000L

class ParseRawRowsTest {
    @Test
    fun `parses content query output`() {
        val out = """
            Row: 0 _id=12, destination_port=134020, date=1761999000000
            Row: 1 _id=13, destination_port=0, date=1761999500000
        """.trimIndent()
        assertEquals(
            listOf(RawRow(12, 134020, 1761999000000), RawRow(13, 0, 1761999500000)),
            parseRawRows(out),
        )
    }

    @Test
    fun `skips rows missing a projected field and non-row lines`() {
        val out = """
            No result found.
            Row: 0 _id=12, date=1761999000000
            Row: 1 destination_port=134020, date=1761999000000
            Row: 2 _id=14, destination_port=134020, date=1761999000000
        """.trimIndent()
        assertEquals(listOf(RawRow(14, 134020, 1761999000000)), parseRawRows(out))
    }

    @Test
    fun `empty output yields no rows`() = assertEquals(emptyList(), parseRawRows(""))

    @Test
    fun `wap push port is matched through the 3gpp flag`() {
        assertTrue(isWapPush(RawRow(1, 134020, 0)))
        assertTrue(isWapPush(RawRow(1, 2948, 0)))
        assertFalse(isWapPush(RawRow(1, 0, 0)))
        assertFalse(isWapPush(RawRow(1, 131072 or 8080, 0)))
    }
}

class StuckIdsTest {
    private fun row(id: Long, date: Long) = RawRow(id, 134020, date)

    @Test
    fun `a row queued longer than two minutes is stuck`() {
        assertEquals(listOf(1L), stuckIds(listOf(row(1, NOW - 3 * MINUTE)), emptyMap(), NOW))
    }

    @Test
    fun `a row mid-delivery is left alone`() {
        assertEquals(emptyList(), stuckIds(listOf(row(1, NOW - 1_000)), emptyMap(), NOW))
    }

    /** The regression this guards: `date` is the sender's SMSC timestamp, so it can be set far in the future. */
    @Test
    fun `a future-dated row is aged from when it was first seen, not from its date`() {
        val poison = row(1, NOW + 365 * 24 * 60 * MINUTE)
        assertEquals(emptyList(), stuckIds(listOf(poison), mapOf(1L to NOW), NOW), "just seen")
        assertEquals(listOf(1L), stuckIds(listOf(poison), mapOf(1L to NOW - 3 * MINUTE), NOW), "seen 3 min ago")
    }

    @Test
    fun `a future-dated row never seen before is not stuck yet`() {
        assertEquals(emptyList(), stuckIds(listOf(row(1, NOW + 10 * MINUTE)), emptyMap(), NOW))
    }

    @Test
    fun `small clock skew still trusts the date`() {
        assertEquals(emptyList(), stuckIds(listOf(row(1, NOW + 30_000)), emptyMap(), NOW))
    }

    @Test
    fun `a first-seen time in the future is clamped instead of hiding the row forever`() {
        val poison = row(1, NOW + 10 * MINUTE)
        assertEquals(emptyList(), stuckIds(listOf(poison), mapOf(1L to NOW + 99 * MINUTE), NOW))
    }

    @Test
    fun `only stuck rows are returned`() {
        val rows = listOf(row(1, NOW - 3 * MINUTE), row(2, NOW - 1_000), row(3, NOW - 10 * MINUTE))
        assertEquals(listOf(1L, 3L), stuckIds(rows, emptyMap(), NOW))
    }
}

class FirstSeenTest {
    @Test
    fun `round trips`() {
        val seen = mapOf(1L to 100L, 2L to 200L)
        assertEquals(seen, decodeFirstSeen(encodeFirstSeen(seen)))
    }

    @Test
    fun `empty map round trips`() = assertEquals(emptyMap(), decodeFirstSeen(encodeFirstSeen(emptyMap())))

    @Test
    fun `malformed entries are dropped rather than throwing`() {
        assertEquals(mapOf(3L to 300L), decodeFirstSeen("junk;1:;:2;a:b;3:300;4:5:6"))
    }
}

class AppOpModeTest {
    @Test
    fun `reads the mode`() {
        assertEquals("allow", parseAppOpMode("WRITE_SMS: allow"))
        assertEquals("ignore", parseAppOpMode("  WRITE_SMS: ignore; rejectTime=+1h2m3s\n"))
    }

    @Test
    fun `falls back to default on unexpected output`() {
        assertEquals("default", parseAppOpMode(""))
        assertEquals("default", parseAppOpMode("Error: no such app op"))
        assertEquals("default", parseAppOpMode("WRITE_SMS: banana"))
    }
}
