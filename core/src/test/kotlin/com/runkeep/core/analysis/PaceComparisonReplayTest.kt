package com.runkeep.core.analysis

import com.runkeep.core.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaceComparisonReplayTest {
    private fun root(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("tools/pace_comparison/compare.py")) }

    private fun command(vararg args: String): String {
        val process = ProcessBuilder(listOf("python3") + args)
            .directory(root().toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
        return output
    }

    @Test
    fun `comparison outputs are reproducible`() {
        assertTrue(command("tools/pace_comparison/compare.py", "--check").contains("deterministic results verified"))
    }

    @Test
    fun `experiment invariants pass`() {
        assertTrue(command("-m", "unittest", "discover", "-s", "tools/pace_comparison", "-p", "test_*.py").contains("OK"))
    }

    @Test
    fun `all synthetic observations match actual production engine distance time segments and pace`() {
        val cases = Json.parseToJsonElement(command("tools/pace_comparison/compare.py", "--trace")).jsonArray
        assertEquals(21, cases.size)
        var observations = 0
        for (case in cases) {
            val name = case.jsonObject.getValue("name").jsonPrimitive.content
            val engine = RunEngine(RunSummary(id = name, startedAt = 0), Preferences(autoPause = false, kilometerVoice = false))
            for (entry in case.jsonObject.getValue("trace").jsonArray) {
                val row = entry.jsonObject
                val now = row.getValue("now").jsonPrimitive.long
                when (row.getValue("action").jsonPrimitive.content) {
                    "pause" -> engine.pause()
                    "resume" -> engine.resume(now)
                }
                row["fix"]?.takeUnless { it == JsonNull }?.jsonObject?.let { fix ->
                    engine.ingest(LocationFix(
                        Coordinate(fix.getValue("lat").jsonPrimitive.double, fix.getValue("lon").jsonPrimitive.double),
                        fix.getValue("at").jsonPrimitive.long,
                        fix.getValue("at").jsonPrimitive.long,
                        fix.getValue("accuracy").jsonPrimitive.float,
                        speed = fix["speed"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.float
                    ), receivedAtMs = now)
                }
                if (row.getValue("tick").jsonPrimitive.boolean) engine.tick(now, now)
                val label = "$name at $now"
                assertEquals(row.getValue("active").jsonPrimitive.long, engine.summary.activeMs, label)
                assertEquals(row.getValue("meters").jsonPrimitive.double, engine.summary.meters, 1e-6, label)
                assertEquals(row.getValue("segment").jsonPrimitive.int, engine.summary.segment, label)
                val expected = row["pace"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.double
                if (expected == null) assertNull(engine.currentPace, label)
                else assertEquals(expected, engine.currentPace ?: Double.NaN, 1e-6, label)
                observations++
            }
        }
        assertTrue(observations > 3000)
        println("PACE_COMPARISON_PRODUCTION_OBSERVATIONS=$observations")
    }
}
