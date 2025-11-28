package sample.app.store

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mvi2.Bootstrapper
import mvi2.BootstrapperScope
import mvi2.Executor
import mvi2.ExecutorScope
import mvi2.Reducer
import mvi2.Store
import mvi2.StoreImpl

sealed interface CounterIntent {
  data object Increment : CounterIntent
  data object Decrement : CounterIntent
}

data class CounterState(
  val count: Int = 0
)

sealed interface CounterLabel

sealed interface CounterMessage {
  data class Update(val value: Int) : CounterMessage
}

sealed interface CounterAction {
  data class Add(val value: Int) : CounterAction
}

fun createCounterStore(
  scope: CoroutineScope
): Store<CounterIntent, CounterState, CounterLabel> =
  StoreImpl(
    initialState = CounterState(),
    scope = scope,
    bootstrapper = CounterBootstrapper(scope),
    executor = CounterExecutor(),
    reducer = CounterReducer(),
  )

private class CounterBootstrapper(private val scope: CoroutineScope) : Bootstrapper<CounterAction> {
  override fun BootstrapperScope<CounterAction>.bootstrap() {
    scope.launch {
      while (true) {
        delay(3000)
        dispatch(CounterAction.Add(5))
      }
    }
  }
}

private class CounterExecutor :
  Executor<CounterIntent, CounterAction, CounterMessage, CounterState, CounterLabel> {
  override fun ExecutorScope<CounterAction, CounterMessage, CounterState, CounterLabel>.executeIntent(
    intent: CounterIntent
  ) {
    val count = state().count

    when (intent) {
      CounterIntent.Increment -> dispatch(CounterMessage.Update(count + 1))
      CounterIntent.Decrement -> dispatch(CounterMessage.Update(count - 1))
    }
  }

  override fun ExecutorScope<CounterAction, CounterMessage, CounterState, CounterLabel>.executeAction(
    action: CounterAction
  ) {
    when (action) {
      is CounterAction.Add -> {
        val count = state().count
        dispatch(CounterMessage.Update(count + action.value))
      }
    }
  }
}

private class CounterReducer : Reducer<CounterState, CounterMessage> {
  override fun reduce(
    state: CounterState,
    message: CounterMessage
  ): CounterState = when (message) {
    is CounterMessage.Update -> state.copy(count = message.value)
  }
}