package dev.gipo.agentreview

import dev.gipo.agentreview.export.ExportOptions
import dev.gipo.agentreview.export.JsonExporter
import dev.gipo.agentreview.export.MarkdownExporter
import dev.gipo.agentreview.model.RepoRange
import dev.gipo.agentreview.model.ReviewSession
import dev.gipo.agentreview.model.Scope
import dev.gipo.agentreview.model.ScopeKind
import dev.gipo.agentreview.scope.RefInput
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceScopeTest {
    private val core = RepoRange("core", base = "aaaaaaaa1111", baseRef = "HEAD~3")
    private val auth = RepoRange("auth", base = "bbbbbbbb2222", head = "cccccccc3333", baseRef = "b1^", headRef = "c3")
    private val ws = Scope(ScopeKind.WORKSPACE, ranges = listOf(auth, core))

    @Test
    fun keyIgnoresRanges() {
        assertEquals("workspace", Scope(ScopeKind.WORKSPACE).key())
        assertEquals("workspace", ws.key())
        assertTrue(ScopeKind.WORKSPACE.followsChangeList)
    }

    @Test
    fun withRangeReplacesTheRepoAndSorts() {
        val widened = core.copy(base = "dddddddd4444", baseRef = "HEAD~5")
        val next = Scope(ScopeKind.WORKSPACE, ranges = listOf(core)).withRange(auth).withRange(widened)
        assertEquals(listOf("auth", "core"), next.ranges.map { it.repo })
        assertEquals("HEAD~5", next.ranges.last().baseRef)
    }

    @Test
    fun labels() {
        assertEquals("HEAD~3..working tree", core.label)
        assertEquals("b1^..c3", auth.label)
        assertEquals("aaaaaaaa..working tree", RepoRange("x", base = "aaaaaaaa1111").label)
        assertEquals("Workspace · 2 repos", ws.shortLabel())
        assertEquals("Workspace · 1 repo", Scope(ScopeKind.WORKSPACE, ranges = listOf(core)).shortLabel())
        assertEquals("workspace: auth b1^..c3, core HEAD~3..working tree", ws.describe())
        assertEquals("workspace (empty)", Scope(ScopeKind.WORKSPACE).describe())
    }

    @Test
    fun serializationKeepsRangesAndLegacyLoads() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        assertEquals(ws, json.decodeFromString(Scope.serializer(), json.encodeToString(Scope.serializer(), ws)))
        val legacy = json.decodeFromString(Scope.serializer(), """{"kind":"RANGE","base":"a","head":"b"}""")
        assertEquals(emptyList<RepoRange>(), legacy.ranges)
    }

    @Test
    fun refInput() {
        assertNull(RefInput.parse("  "))
        assertEquals(RefInput("HEAD~3", mergeBase = false), RefInput.parse(" HEAD~3 "))
        assertEquals(RefInput("main", mergeBase = true), RefInput.parse("main..."))
    }

    @Test
    fun jsonCarriesRanges() {
        val out = JsonExporter.encode(JsonExporter.session(ReviewSession(scope = ws), emptyList(), false, null))
        assertTrue(out, out.contains("\"scope_kind\": \"workspace\""))
        assertTrue(out, out.contains("\"repo\": \"core\""))
        assertTrue(out, out.contains("\"head\": null"))
        assertTrue(out, out.contains("\"label\": \"b1^..c3\""))
    }

    @Test
    fun markdownListsEachRepo() {
        val md = MarkdownExporter.export(ReviewSession(scope = ws), emptyList(), ExportOptions(branch = "main", mcpHint = false))
        assertTrue(md, md.contains("Scope: workspace\n"))
        assertTrue(md, md.contains("- auth: b1^..c3 (`git diff bbbbbbbb2222 cccccccc3333`)\n"))
        assertTrue(md, md.contains("- core: HEAD~3..working tree (`git diff aaaaaaaa1111`)\n"))
        assertTrue(md, !md.contains("on `main`"))
    }
}
