package io.github.pimobile.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ShapesTest {
    /** What pi-subagents sends while 3 helpers run side by side (trimmed). */
    private val subagentsLive = JSONObject("""{"mode":"parallel","results":[
        {"agent":"scout","task":"find auth","exitCode":0,"usage":{"cost":0.012}},
        {"agent":"planner","task":"plan it","exitCode":-1,"usage":{"cost":0.003}},
        {"agent":"reviewer","task":"review","exitCode":-1,"usage":{"cost":0}}],
      "progress":[
        {"index":0,"agent":"scout","status":"completed","task":"find auth","toolCount":6,"tokens":900,"durationMs":21000,"recentTools":[],"recentOutput":["found it"]},
        {"index":1,"agent":"planner","status":"running","task":"plan it","currentTool":"read","currentToolArgs":"src/auth.ts","toolCount":2,"tokens":300,"durationMs":9000,"recentTools":[],"recentOutput":[]},
        {"index":2,"agent":"reviewer","status":"pending","task":"review","toolCount":0,"tokens":0,"durationMs":0,"recentTools":[],"recentOutput":[]}]}""")

    @Test fun subagentsBecomeACrew() {
        val c = Shapes.crew(subagentsLive)
        assertNotNull(c); c!!
        assertEquals("parallel", c.mode)
        assertEquals(listOf("scout", "planner", "reviewer"), c.lanes.map { it.name })
        assertEquals(listOf(Shapes.LaneState.DONE, Shapes.LaneState.RUNNING, Shapes.LaneState.WAITING), c.lanes.map { it.state })
        assertEquals("read  src/auth.ts", c.lanes[1].now)
        assertEquals(1, c.running)
        assertEquals(0.015, c.cost, 1e-9)
        // A crew is never also a checklist.
        assertNull(Shapes.checklist(subagentsLive, ""))
    }

    @Test fun finalResultsOnlyStillACrew() {
        val c = Shapes.crew(JSONObject("""{"mode":"chain","results":[{"agent":"a","task":"x","exitCode":0},{"agent":"b","task":"y","exitCode":1,"error":"boom"}]}"""))!!
        assertEquals(listOf(Shapes.LaneState.DONE, Shapes.LaneState.FAILED), c.lanes.map { it.state })
        assertEquals("boom", c.lanes[1].error)
    }

    @Test fun todoListIsAChecklist() {
        val d = JSONObject("""{"todos":[{"id":1,"content":"Read code","status":"completed"},{"id":2,"content":"Fix bug","status":"in_progress"},{"id":3,"content":"Test","status":"pending"}]}""")
        assertNull(Shapes.crew(d))
        val c = Shapes.checklist(d, "")!!
        assertEquals(listOf(true, false, false), c.map { it.done })
        assertEquals(listOf(false, true, false), c.map { it.active })
    }

    @Test fun markdownBoxesInTextAreAChecklistExceptForFileTools() {
        val text = "Plan:\n- [x] one\n- [ ] two\n- [ ] three"
        assertEquals(3, Shapes.checklist(null, text)!!.size)
        assertNull(Shapes.checklist(null, text, core = true))
    }

    @Test fun progressShapes() {
        assertEquals(0.25f, Shapes.progress(JSONObject("""{"completed":5,"total":20}"""))!!.fraction, 1e-6f)
        assertEquals(0.4f, Shapes.progress(JSONObject("""{"percent":40,"stage":"indexing"}"""))!!.fraction, 1e-6f)
        assertNull(Shapes.progress(JSONObject("""{"path":"a.txt"}""")))
    }

    @Test fun plainToolsGetNothing() {
        val d = JSONObject("""{"truncated":false,"lines":[{"n":1,"text":"hello"},{"n":2,"text":"world"}]}""")
        assertNull(Shapes.crew(d)); assertNull(Shapes.checklist(d, "")); assertNull(Shapes.progress(d))
    }
}
