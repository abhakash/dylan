package dylan.bridge

import dylan.model.PlayerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

class KotlinSubscription internal constructor(
    private val job: Job,
) {
    fun cancel() {
        job.cancel()
    }
}

class FlowAdapter<T : Any>(
    private val flow: Flow<T>,
    private val scope: CoroutineScope,
) {
    fun subscribe(
        onEach: (T) -> Unit,
        onError: (Throwable) -> Unit = { dylan.util.logErr("dylan-bridge: ${it.message}") },
        onComplete: () -> Unit = {},
    ): KotlinSubscription =
        KotlinSubscription(
            scope.launch(Dispatchers.Main.immediate) {
                try {
                    flow
                        .conflate()
                        .flowOn(Dispatchers.Default)
                        .collect { onEach(it) }
                    onComplete()
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    onError(t)
                }
            },
        )
}

class PlayerStateAdapter(
    scope: CoroutineScope,
    flow: Flow<PlayerState>,
    onEach: (PlayerState) -> Unit,
) {
    private val inner = FlowAdapter(flow, scope)

    fun subscribe(onEach: (PlayerState) -> Unit): KotlinSubscription = inner.subscribe(onEach)
}

class PositionAdapter(
    scope: CoroutineScope,
    flow: Flow<Long>,
) {
    private val inner = FlowAdapter(flow.conflate(), scope)

    fun subscribe(onEach: (Long) -> Unit): KotlinSubscription = inner.subscribe(onEach)
}
