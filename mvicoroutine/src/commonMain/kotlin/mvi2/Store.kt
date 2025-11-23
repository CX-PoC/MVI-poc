package mvi2

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface Store<Intent, State, Label> {
  val state: StateFlow<State>
  val labels: Flow<Label>
  /**
   * Process an Intent.
   *
   * Guarantee (synchronous part):
   * - When this method returns, all messages/actions synchronously
   *   dispatched from this intent (and their chains) have already
   *   produced their state updates.
   *
   * Additional updates from async work launched in Executor/Bootstrapper
   * may arrive later.
   */
  fun sendIntent(intent: Intent)
  /**
   * Starts the store (runs bootstrapper, etc.).
   * Idempotent and thread-safe.
   *
   * When autoInit = false, you must call this manually.
   */
  fun init()
  /**
   * Cancels internal coroutines and prevents further processing.
   * Idempotent.
   */
  fun dispose()
}

fun interface Reducer<State, Message> {
  fun reduce(state: State, message: Message): State
}

fun interface Bootstrapper<Action> {
  fun bootstrap(dispatchAction: (Action) -> Unit)
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
  fun state(): State
  val coroutineScope: CoroutineScope

  fun dispatch(message: Message)
  fun tryEmit(label: Label): Boolean
  fun dispatchAction(action: Action)
}

/**
 * Executor handles both Intents and Actions.
 */
interface Executor<Intent, Action, State, Message, Label> {
  fun ExecutorScope<Intent, Action, State, Message, Label>.executeIntent(intent: Intent)
  fun ExecutorScope<Intent, Action, State, Message, Label>.executeAction(action: Action)
}