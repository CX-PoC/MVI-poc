package mvi

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toCollection
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue


sealed interface CounterIntent {
  data object Increment : CounterIntent
}

sealed interface CounterAction {
  data object Init : CounterAction
}

sealed interface CounterMessage {
  data object Incremented : CounterMessage
  data class Set(val value: Int) : CounterMessage
}

data class CounterState(val value: Int = 0, val initialized: Boolean = false)

sealed interface CounterLabel

private val reducer = Reducer<CounterState, CounterMessage> { state, msg ->
  when (msg) {
    CounterMessage.Incremented -> state.copy(value = state.value + 1)
    is CounterMessage.Set -> state.copy(value = msg.value, initialized = true)
  }
}

private class TestExecutor :
  Executor<CounterIntent, CounterAction, CounterState, CounterMessage, CounterLabel> {
  override suspend fun ExecutorScope<CounterIntent, CounterAction, CounterState, CounterMessage, CounterLabel>.executeIntent(
    intent: CounterIntent
  ) {
    println("executeIntent: $intent")
    delay(200) // test out do we really guarantee only one at a time?
    when (intent) {
      CounterIntent.Increment -> dispatch(CounterMessage.Incremented)
    }
  }

  override suspend fun ExecutorScope<CounterIntent, CounterAction, CounterState, CounterMessage, CounterLabel>.executeAction(
    action: CounterAction
  ) {
    println("executeAction: $action")
    delay(500) // test out do we really guarantee only one at a time?
    when (action) {
      CounterAction.Init -> dispatch(CounterMessage.Set(10))
    }
  }
}

private class TestBootstrapper : Bootstrapper<CounterAction> {
  override suspend fun bootstrap(dispatchAction: suspend (CounterAction) -> Unit) {
    dispatchAction(CounterAction.Init)
  }
}


@OptIn(ExperimentalCoroutinesApi::class)
class CoroutineStoreTest {

  @Test
  fun testSynchronousStateUpdate() = runTest {
    val dispatcher = UnconfinedTestDispatcher(testScheduler)
    val store = CoroutineStore(
      initialState = CounterState(),
      bootstrapper = null,
      executorFactory = { TestExecutor() },
      reducer = reducer,
      dispatcher = dispatcher
    )

    assertEquals(0, store.state.value.value)
    println("Sending Increment 1")
    store.sendIntent(CounterIntent.Increment)
    println("Done sending Increment 1")
    assertEquals(1, store.state.value.value)
    println("Sending Increment 2")
    store.sendIntent(CounterIntent.Increment)
    println("Done sending Increment 2")
    assertEquals(2, store.state.value.value)
    store.dispose()
  }

  @Test
  fun testAutoInit() = runTest {
    val dispatcher = UnconfinedTestDispatcher(testScheduler)
    val store = CoroutineStore(
      initialState = CounterState(),
      bootstrapper = TestBootstrapper(),
      executorFactory = { TestExecutor() },
      reducer = reducer,
      dispatcher = dispatcher,
      autoInit = true
    )

    store.sendIntent(CounterIntent.Increment)

    // what should happen here is that
    // even if bootstrapper has 500ms delay, it still go first, and then intent go after
    store.dispose()
    println("state: ${store.state.value}")
  }

  @Test
  fun testManualInit() = runTest {
    val dispatcher = UnconfinedTestDispatcher(testScheduler)
    val store = CoroutineStore(
      initialState = CounterState(),
      bootstrapper = TestBootstrapper(),
      executorFactory = { TestExecutor() },
      reducer = reducer,
      dispatcher = dispatcher,
      autoInit = false
    )

    store.init()

    store.sendIntent(CounterIntent.Increment)
    store.dispose()

    println("state: ${store.state.value}")

  }
}
