package mvi

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Coroutine-first MVIKotlin-like store.
 *
 * Guarantees:
 *  - Only one Intent/Action chain is processed at a time (critical section).
 *  - When sendIntent/dispatchAction return, all synchronous state updates in that chain are applied.
 *
 * Default dispatcher: Dispatchers.Main.immediate (KMP target must provide it),
 * but you can override.
 */
class CoroutineStore<Intent, Action, Message, State, Label>(
  initialState: State,
  private val bootstrapper: Bootstrapper<Action>?,
  private val executor: Executor<Intent, Action, State, Message, Label>,
  private val reducer: Reducer<State, Message>,
  private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
  private val autoInit: Boolean = true
) : Store<Intent, State, Label> {

  private val job = SupervisorJob()
  private val scope = CoroutineScope(dispatcher + job)

  private val _state = MutableStateFlow(initialState)
  override val state: StateFlow<State> = _state.asStateFlow()

  private val _labels = MutableSharedFlow<Label>()
  override val labels: SharedFlow<Label> = _labels.asSharedFlow()
  // Serialize top-level Intent/Action chains.
  private val intentActionMutex = Mutex()

  // Start control
  private val startMutex = Mutex()
  private var started = false

  // Context element marking we're already inside the critical section.
  private object InChain : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<InChain>
  }

  private inner class ExecutorScopeImpl : ExecutorScope<Intent, Action, State, Message, Label> {

    override val state: State
      get() = _state.value

    override val coroutineScope: CoroutineScope
      get() = scope

    override suspend fun dispatch(message: Message) {
      withContext(scope.coroutineContext) {
        _state.value = reducer.reduce(_state.value, message)
      }
    }

    override suspend fun publish(label: Label) {
      withContext(scope.coroutineContext) {
        _labels.emit(label)
      }
    }

    override suspend fun dispatchAction(action: Action) {
      // If we're already inside a chain, inline without re-locking.
      if (currentCoroutineContext()[InChain.Key] != null) {
        processActionChain(action) // assumes we're on store context (we are)
      } else {
        // External/late call: serialize like a top-level Action.
        withContext(scope.coroutineContext) {
          intentActionMutex.withLock {
            withContext(InChain) {
              processActionChain(action)
            }
          }
        }
      }
    }
  }

  private val execScope = ExecutorScopeImpl()

  init {
    if (autoInit) {
      // Fire-and-forget; bootstrapper will run in store scope.
      scope.launch { startIfNeeded() }
    }
  }

  override fun init() {
    startIfNeeded()
  }

  private fun startIfNeeded() {
    scope.launch {
      startMutex.withLock {
        if (started) return@launch
        started = true
        bootstrapper?.let { bs ->
          // Run bootstrapper; it can suspend and dispatch actions.
          bs.init()
          executor.init()
          bs.bootstrap { action ->
            // Bootstrapper's actions must go through the same serialization.
            sendActionExternal(action)
          }
        }
      }
    }

  }

  override suspend fun sendIntent(intent: Intent) {
    // Note: sending intents does not implicitly start the bootstrapper
    // when autoInit=false. Call init() yourself if you rely on bootstrapper.
    withContext(scope.coroutineContext) {
      intentActionMutex.withLock {
        withContext(InChain) {
          processIntentChain(intent)
        }
      }
    }
  }

  override fun dispose() {
    job.cancel()
    bootstrapper?.dispose()
    executor.dispose()
  }

  // ----------------- Internal helpers -----------------

  private suspend fun processIntentChain(intent: Intent) {
    with(executor) { execScope.executeIntent(intent) }
  }

  private suspend fun processActionChain(action: Action) {
    with(executor) { execScope.executeAction(action) }
  }

  private suspend fun sendActionExternal(action: Action) {
    // For bootstrapper or any external (non-executor) producer.
    withContext(scope.coroutineContext) {
      intentActionMutex.withLock {
        withContext(InChain) {
          processActionChain(action)
        }
      }
    }
  }
}
