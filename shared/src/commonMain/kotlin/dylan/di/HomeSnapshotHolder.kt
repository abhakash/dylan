package dylan.di

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Holder for the Android Home screen's loaded projection. It is UI state, not graph state: iOS
 * has its own `HomeStore` and never reads it, so the graph carries a *reference* to a holder the
 * platform layer owns rather than the projection itself.
 *
 * Android migration: build one [HomeSnapshotHolder] in `DylanApp.onCreate`, pass it to
 * `AppContainer(home = …)`, and hand the same instance to `HomeScreen` as a parameter. Then the
 * [AppContainer.homeSnapshot] accessor and the nested `AppContainer.HomeSnapshot` type are
 * deletable and the graph stops carrying Android UI state at all.
 */
class HomeSnapshotHolder(
    initial: AppContainer.HomeSnapshot = AppContainer.HomeSnapshot(),
) {
    val state: MutableStateFlow<AppContainer.HomeSnapshot> = MutableStateFlow(initial)
}
