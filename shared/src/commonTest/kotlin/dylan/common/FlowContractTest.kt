package dylan.common

import app.cash.turbine.test
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * commonTest canary.
 *
 * Its job is to make the `commonTest` source set non-empty so it is compiled
 * into every target's test compilation and executed by `:shared:jvmTest` and
 * `:shared:iosSimulatorArm64Test`. An empty source set is silently dropped, which
 * is how `commonTest.dependencies` went on declaring `turbine` with zero uses.
 *
 * It doubles as the reference Flow-assertion pattern for common code: the
 * platform-agnostic tests should use Turbine, not `first()`/`toList()`.
 */
class FlowContractTest {
    @Test
    fun emitsEveryValueInOrderThenCompletes() =
        runTest {
            flowOf(1, 2, 3).test {
                assertEquals(1, awaitItem())
                assertEquals(2, awaitItem())
                assertEquals(3, awaitItem())
                awaitComplete()
            }
        }

    @Test
    fun terminalFailureSurfacesToTheCollector() =
        runTest {
            flow {
                emit(7)
                error("upstream died")
            }.test {
                assertEquals(7, awaitItem())
                awaitError()
            }
        }

    @Test
    fun hotFlowCancellationIsNotAnError() =
        runTest {
            MutableSharedFlow<Int>().test {
                cancelAndIgnoreRemainingEvents()
            }
        }
}
