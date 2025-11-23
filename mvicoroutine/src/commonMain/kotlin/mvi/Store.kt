package mvi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow



/**
 * Public-facing Store interface.
 *
 * @param Intent From UI to store.
 * @param State UI state.
 * @param Label One-off events (navigation, toasts, etc.).
 */
interface Store<Intent, State, Label> {
  val state: StateFlow<State>
  val labels: Flow<Label>

  /**
   * Main entry point for UI.
   * Guarantees (if you don't manually switch dispatcher inside the library user code):
   *  - When this returns, all synchronous state updates from this intent are applied.
   *  - Only one intent/action is being processed at a time.
   */
  suspend fun sendIntent(intent: Intent)

  /**
   * When autoInit = false, you must call init() to start the store (bootstrapper).
   * Idempotent: calling multiple times is safe.
   */
  fun init()

  fun dispose()
}

/**
 * Bootstrapper produces Actions when the store starts (or over time).
 * Only the bootstrapper and executor can dispatch Actions.
 */
fun interface Bootstrapper<Action> {
  suspend fun bootstrap(dispatchAction: suspend (Action) -> Unit)

  fun init() {}
  fun dispose() {}
}

/**
 * Pure function: (State, Message) -> State.
 */
fun interface Reducer<State, Message> {
  fun reduce(state: State, msg: Message): State
}

/**
 * Scope available to the Executor during processing.
 * Lets the Executor:
 *  - read current state
 *  - dispatch Messages (state changes)
 *  - publish Labels (one-off events)
 *  - dispatch Actions (chained internal pipelines)
 *  - launch additional work on store's CoroutineScope if desired
 */
interface ExecutorScope<Intent, Action, State, Message, Label> {
  /**
   * Read-only state. Implementation should use `get()` delegate
   */
  val state: State
  val coroutineScope: CoroutineScope

  suspend fun dispatch(message: Message)
  suspend fun publish(label: Label)
  suspend fun dispatchAction(action: Action)
}

/**
 * Executor handles both Intents and Actions.
 */
interface Executor<Intent, Action, State, Message, Label> {
  suspend fun ExecutorScope<Intent, Action, State, Message, Label>.executeIntent(intent: Intent)
  suspend fun ExecutorScope<Intent, Action, State, Message, Label>.executeAction(action: Action)

  fun init() {}
  fun dispose() {}
}
