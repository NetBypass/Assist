package ai.arena.assist.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EndpointNormalizerTest {
    @Test
    fun acceptsV1BaseUrl() {
        assertEquals(
            "https://example.test/v1/chat/completions",
            EndpointNormalizer.chatCompletionsUrl("https://example.test/v1/")
        )
    }

    @Test
    fun addsV1ToOrigin() {
        assertEquals(
            "http://192.168.1.5:8080/v1/models",
            EndpointNormalizer.modelsUrl("http://192.168.1.5:8080")
        )
    }

    @Test
    fun preservesProxyPrefixAndConvertsFullChatUrl() {
        assertEquals(
            "https://example.test/proxy/openai/v1/models",
            EndpointNormalizer.modelsUrl("https://example.test/proxy/openai/v1/chat/completions")
        )
    }

    @Test
    fun rejectsNonHttpSchemes() {
        assertThrows(IllegalArgumentException::class.java) {
            EndpointNormalizer.chatCompletionsUrl("file:///tmp/api")
        }
    }
}
