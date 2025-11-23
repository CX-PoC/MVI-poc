package mvi2

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class StoreImpl<Intent, Action, State, Message, Label> (
  initialState: State,
  autoInit: Boolean = true,
  private val bootstrapper: Bootstrapper<Action>? = null,
  private val executor: Executor<Intent, Action, State, Message, Label>,
  private val reducer: Reducer<State, Message>,
  private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
  labelFlow: MutableSharedFlow<Label> = MutableSharedFlow(),
) : Store<Intent, State, Label> {

  private val job = SupervisorJob()
  private val scope: CoroutineScope = CoroutineScope(dispatcher + job)

  private val _state = MutableStateFlow(initialState)
  override val state: StateFlow<State> = _state

  private val _label = labelFlow
  override val labels: MutableSharedFlow<Label> = _label

  private inner class ExecutorScopeImpl : ExecutorScope<Intent, Action, State, Message, Label> {
    override fun state() = _state.value

    override val coroutineScope: CoroutineScope = scope

    override fun dispatch(message: Message) {
      _state.value = reducer.reduce(_state.value, message)
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
    bootstrapper?.let { bs ->
      bs.bootstrap { action ->
        with(executor) {
          executorScope.executeAction(action)
        }
      }
    }
  }

  override fun dispose() {
    job.cancel()
  }

}