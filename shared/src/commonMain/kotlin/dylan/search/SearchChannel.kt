package dylan.search

import dylan.model.MiniEntity

interface SearchChannel {
    suspend fun suggest(query: String): List<MiniEntity>
}
