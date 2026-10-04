package ai.arena.assist.api

import java.net.URI

object EndpointNormalizer {
    fun chatCompletionsUrl(raw: String): String = build(raw, "chat/completions")
    fun modelsUrl(raw: String): String = build(raw, "models")

    private fun build(raw: String, target: String): String {
        val value = raw.trim().trimEnd('/')
        require(value.isNotBlank()) { "Endpoint is required" }
        val uri = runCatching { URI(value) }
            .getOrElse { throw IllegalArgumentException("Endpoint is not a valid URL") }
        require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
            "Endpoint must start with http:// or https://"
        }
        require(!uri.rawAuthority.isNullOrBlank()) { "Endpoint host is missing" }

        var path = uri.rawPath.orEmpty().trimEnd('/')
        path = when {
            path.endsWith("/chat/completions") -> path.removeSuffix("/chat/completions")
            path.endsWith("/models") -> path.removeSuffix("/models")
            else -> path
        }
        if (!path.endsWith("/v1")) path += "/v1"
        path += "/$target"

        return URI(uri.scheme.lowercase(), uri.rawAuthority, path, null, null).toASCIIString()
    }
}
