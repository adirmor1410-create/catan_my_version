package com.catan.core.net

import kotlinx.serialization.json.Json

/**
 * The one JSON configuration used by the server and the app.
 *
 * `allowStructuredMapKeys` matters: roads and buildings are keyed by [com.catan.core.model.EdgeId]
 * and [com.catan.core.model.VertexId], which are objects rather than strings, and plain JSON
 * cannot express that without it.
 */
val CatanJson: Json = Json {
    allowStructuredMapKeys = true
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "kind"
}
