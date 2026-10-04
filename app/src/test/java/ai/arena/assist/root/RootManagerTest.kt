package ai.arena.assist.root

import org.junit.Assert.assertEquals
import org.junit.Test

class RootManagerTest {
    @Test
    fun safelyQuotesShellArguments() {
        assertEquals("'hello'\\'' world'", RootManager.shellQuote("hello' world"))
    }
}
