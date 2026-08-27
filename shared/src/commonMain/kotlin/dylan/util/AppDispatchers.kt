package dylan.util

import kotlinx.coroutines.CoroutineDispatcher

class AppDispatchers(
    val main: CoroutineDispatcher,
    val io: CoroutineDispatcher,
    val dbLane: CoroutineDispatcher,
    val state: CoroutineDispatcher,
)
