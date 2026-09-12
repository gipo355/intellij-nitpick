package dev.gipo.agentreview

import dev.gipo.agentreview.scope.ReviewPaths
import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewPathsTest {

    @Test
    fun underTheProjectBaseStaysRelativeToIt() {
        assertEquals("src/A.kt", ReviewPaths.relative("/p", "/p", "/p/src/A.kt"))
        assertEquals("core/src/A.kt", ReviewPaths.relative("/ws", "/ws/core", "/ws/core/src/A.kt"))
    }

    @Test
    fun outsideTheProjectBaseGetsTheRepoIdPrefix() {
        assertEquals("core/src/A.kt", ReviewPaths.relative("/ws", "/repos/core", "/repos/core/src/A.kt"))
        assertEquals("core/src/A.kt", ReviewPaths.relative(null, "/repos/core", "/repos/core/src/A.kt"))
        assertEquals("/x/y", ReviewPaths.relative(null, null, "/x/y"))
    }

    @Test
    fun repoIdIsTheRootRelativeToTheBaseElseItsFolderName() {
        assertEquals("core", ReviewPaths.repoId("/ws", "/ws/core"))
        assertEquals("server/core", ReviewPaths.repoId("/ws", "/ws/server/core"))
        assertEquals("core", ReviewPaths.repoId("/ws", "/repos/core"))
        assertEquals("core", ReviewPaths.repoId(null, "/repos/core"))
    }

    @Test
    fun candidatesTryTheBaseThenThePrefixedRepoThenEveryRepo() {
        val roots = listOf("/repos/core", "/repos/iam")
        assertEquals(
            listOf("/ws/core/src/A.kt", "/repos/core/src/A.kt", "/repos/core/core/src/A.kt", "/repos/iam/core/src/A.kt"),
            ReviewPaths.candidates("/ws", roots, "core/src/A.kt"),
        )
        assertEquals(listOf("/repos/core/", "/repos/iam/"), ReviewPaths.candidates(null, roots, ""))
        // A bare repo id is the repo root itself.
        assertEquals(listOf("/repos/core", "/repos/core/core", "/repos/iam/core"), ReviewPaths.candidates(null, roots, "core"))
    }
}
