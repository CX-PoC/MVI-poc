package mvi2

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class StoreImpl<Intent, Action, Message, State, Label> (
  initialState: State,
  private val scope: CoroutineScope,
  autoInit: Boolean = true,
  private val bootstrapper: Bootstrapper<Action>? = null,
  private val executor: Executor<Intent, Action, Message, State, Label>,
  private val reducer: Reducer<State, Message>,
  private val _label: MutableSharedFlow<Label> = MutableSharedFlow(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST),
) : Store<Intent, State, Label> {


  private val _state = MutableStateFlow(initialState)
  override val state: StateFlow<State> = _state

  override val labels: SharedFlow<Label> = _label

  private inner class BootstrapperScopeImpl : BootstrapperScope<Action> {
    override fun dispatch(action: Action) {
      executorScope.dispatchAction(action)
    }
  }

  private inner class ExecutorScopeImpl : ExecutorScope<Action, Message, State, Label> {
    override fun state() = _state.value

    override fun dispatch(message: Message) {
      _state.update { reducer.reduce(it, message) }
    }

    override fun tryEmit(label: Label): Boolean {
      return _label.tryEmit(label)
    }

    override fun dispatchAction(action: Action) {
      with(executor) {
        executeAction(action)
      }
    }
  }

  private val bootstrapperScope = BootstrapperScopeImpl()
  private val executorScope = ExecutorScopeImpl()

  init {
    if (autoInit) {
      init()
    }
  }

  override fun sendIntent(intent: Intent) {
    with(executor) {
      executorScope.executeIntent(intent)
    }
  }

  override fun init() {
    executor.init()
    bootstrapper?.init()
    bootstrapper?.let { bs ->
      with(bs) {
        bootstrapperScope.bootstrap()
      }
    }
  }

  override fun dispose() {
    bootstrapper?.dispose()
    executor.dispose()
  }

}