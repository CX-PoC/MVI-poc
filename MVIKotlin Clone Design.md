# MVIKotlin Clone

## 🧑‍💻 User

I love MVIKotlin, but I don't like how he has its own reactive library, I would like to do a clone of MVIKotlin but prefer a more coroutine first approach.

requirements:

1. preserve the 3 components: bootstrapper, executor, reducer
2. 5 objects: intent, action, message, state, label
3. preserve the requirement where:
    - if user didn't manually switch the dispatcher, when sendIntent sendAction function returns, the state is guaranteed already changed.
    - if user didn't manually switch the dispatcher, only one intent/action is processed at a time.

---

## 🤖 Assistant

<div style="border: 2px solid #dddddd; border-radius: 10px;">
  <details style="padding: 5px;">
    <summary>Deep reasoning</summary>
    **Designing coroutine-first architecture**<br><br>I’m working on outlining the architecture for a "clone of MVIKotlin but coroutine-first." The aim is to incorporate three components: bootstrapper, executor, and reducer, along with five objects: intent, action, message, state, and label. I need to ensure synchronous-like processing without dispatcher switching and strictly sequential handling of intents/actions. I'll consider using Kotlin coroutines, possibly with StateFlow and SharedFlow. I also want to clarify aspects like thread safety and multi-platform direction after presenting the initial design.**Proposing architecture clarification**<br><br>The instructions emphasize that I should ask for clarification when something is unclear or conflicting with user requests. While I have enough information to propose a generic design, I'm wondering if they want a multiplatform approach or if they prefer a shorter or more detailed explanation. The system defaults to a verbosity level of 2, which suggests some detail is expected. I need to focus on a coroutine-first architecture, considering a single coroutine actor that processes one event at a time while ensuring state updates before proceeding to the next.**Navigating coroutine complexities**<br><br>I'm thinking through the challenges of using coroutines with `sendIntent`. If `sendIntent` is a regular function and not a suspend function, it complicates processing into a `Channel` on the same dispatcher. I could consider using a single-threaded dispatcher or a `Mutex`. To make guarantees about state updates, I might need to block using `runBlocking`, or I could process everything inside a sequential `sendIntent`. Alternatively, making `sendIntent` a suspend function could help manage state with enqueued requests. The requirements state that when `sendIntent` returns, the state should be updated, even though they didn’t clarify if it’s synchronous. In MVIKotlin, `accept()` provides immediate state output, but with coroutines, I wonder if I can manage execution in the same thread.**Considering coroutine-first design**<br><br>It seems that the user wants a coroutine-first approach, which would mean managing effects, internal variables, and other elements with coroutines. One alternative could be keeping the main pipeline synchronous using standard Kotlin while only handling side effects and asynchronous work through coroutines initiated from the Executor. The original MVIKotlin utilized a reactive library for asynchronous flows in the Executor, and when it says, "when user doesn't switch dispatcher," I assume it refers to using immediate or single-threaded dispatchers. A coroutine-based design allows processing from intent to state in the same call stack, where `sendIntent` would transform to an `Action` and pass it to the Executor for asynchronous tasks. For immediate logic like state updates, we might directly call the reducer, while asynchronous tasks would modify state later on.**Addressing sendIntent guarantees**<br><br>The guarantee that "when sendIntent returns, state changed" holds true only for immediate changes due to synchronous messages. If the Executor uses `launch` with a delay, the state won't reflect that change immediately after `sendIntent`. I think MVIKotlin’s guarantees focus on synchronous work before asynchronous boundaries. To mirror that behavior, synchronous messages from the Executor must be processed before `sendIntent` returns, ensuring no concurrency with other intents. Messages from coroutines should also be sequentially delivered, but they can occur later. For additional safety, using a `Mutex` or single-threaded context to protect the state and a blocking strategy to run processes could work. The design needs to allow for `sendIntent` to be either synchronous or suspend but should lean toward coroutines-first for better structured concurrency. We might suggest two function styles: one synchronous for UI threads and a suspend version, and we should clarify user preferences on this. We’ll ensure internal pipelines execute on the same dispatcher used for processing, ultimately relying on the instance-level `CoroutineDispatcher`. Overall, the design needs to account for possible re-entrancy while maintaining the synchronous semantics for dispatching messages.**Designing intent dispatch guarantees**<br><br>I believe we can simplify lock usage by ensuring that `dispatch` is only called while holding the lock, avoiding reacquiring it. We could create two methods: `dispatchSync` to inline calls in the same context, or process the overall intent pipeline by building a list of messages to reduce after unlocking, though that feels clumsy. Instead, I think it’s better to rely on the `Mutex` for outer intents/actions, allowing inner messages to reduce inline without extra concurrency. By ensuring no two `sendIntent` calls can execute `executor.handleIntent` concurrently using a lock, we meet the “only one intent/action processed at a time” requirement. Now, the logic behind state updates follows naturally during synchronous calls, meaning that as long as `dispatch(message)` runs on the same thread, updates happen when `sendIntent` returns, maintaining correctness regardless of the dispatcher used.<br><br>**Exploring event processing**<br><br>I'm thinking about using a `Channel` plus `actor` style for representing a store as an event loop. In this setup, a single coroutine collects events from this channel and processes them one by one. However, I see a challenge here: we can't easily fulfill guarantee 1 without some kind of bridging. It feels like there's more to unpack in this design approach, and I'm curious how to maintain the guarantees while keeping it efficient!**Implementing event processing**<br><br>I'm considering implementing `sendIntent` as a `suspend` function that sends requests to a channel, along with `CompletableDeferred`. This involves defining a `StoreEvent` sealed class for handling intents and actions. In my setup, I have an event channel that processes events sequentially with a single consumer.<br><br>Now, the semantics of this approach ensure that events are processed one after another, even when they come from asynchronous tasks. I need to make sure that the `dispatch` function returns immediately, so I’ll use `launch` appropriately to handle message events without blocking.**Analyzing ack semantics**<br><br>I’m realizing that the ack semantics for `sendIntent` ensures completion only after the `executor.handleIntent` method returns. However, message events could still be pending in the queue, meaning their state changes won't be applied before the ack completes, which contradicts my requirements. I need the ack to finish only after all synchronous messages triggered by that intent have been handled. <br><br>Currently, events are processed sequentially: `HandleIntent` runs, and if it dispatches messages that queue afterwards, they won’t be processed until the event loop continues. This seems asynchronous from the `HandleIntent` perspective, which complicates things as the ack would complete too early. I'm trying to find a solution!**Clarifying ack behavior**<br><br>I've realized that the ack is independent and gets completed while still processing the `HandleIntent` event. However, since the event loop is sequential, the ack completion happens before starting the next event. The `DispatchMessage` events are already queued, which creates a moment of ordering. The ack is called before processing these messages, but we need the state to be updated when `sendIntent` returns.<br><br>Currently, `sendIntent` creates an ack, sends `HandleIntent`, and awaits its completion. However, that happens before processing the `DispatchMessage` events, which isn’t ideal. To resolve this, I think we need to process the `DispatchMessage` events inline within the `HandleIntent` flow rather than queueing them for later.**Exploring concurrency and dispatcher**<br><br>I'm analyzing the use of actor-channel patterns for intents and messages in my design. I realize that if `executor.handleIntent` calls `dispatch`, it must call the reducer directly to avoid queuing issues. I want to ensure that asynchronous dispatches don’t change the state in parallel. To manage this, I can use a `Mutex` or atomic properties of `MutableStateFlow`. But I need to ensure that the reducer runs sequentially to avoid race conditions.<br><br>The complexity of threading further complicates things. I want to keep the dispatch non-blocking while ensuring synchronous behavior in the pipeline. To address this, I propose making `dispatch` a `suspend` function, allowing us to manage state updates inline without queuing.<br><br>My plan involves specific interfaces for handling intents and actions, coordinating the concurrency properly within the store. I also need to consider how users might prefer simpler `sendIntent` implementations that maintain synchronous semantics while aiming for multi-platform compatibility. This is interesting, and I need to refine my thoughts on these aspects!**Designing the interactions**<br><br>I’m working through the design in an Android Compose environment, where calling `store.accept(intent)` non-suspending can simplify UI event handling. I need to figure out how the bootstrapper interacts with the executor. Since bootstrapping is asynchronous, we’ll call `bootstrapper.bootstrap()` within a `scope.launch` without blocking the store initialization. <br><br>To manage concurrent actions, I'm thinking of using `Mutex` for synchronization, ensuring that actions produced respond to intents without causing deadlocks. My plan also involves refined use of interfaces, helping maintain our crucial objects—like intent, action, message, state, and label—throughout the design. <br><br>I must ensure that actions triggered by the bootstrapper or executor don’t run simultaneously, maintaining actor semantics, and possibly isolate action handling to the bootstrapper to avoid further complications. It’s a balancing act, but I’m optimistic about wrapping this up neatly!**Refining the design**<br><br>I’m working on refining the design. The `Store` will utilize an `event queue Channel`, handling events such as `Intent` or `Action` with acknowledgments. A single coroutine will process these events sequentially, ensuring that state changes occur only after `sendIntent` completes all synchronous operations. To manage the acknowledgment properly, I need to structure the event as a "Work Unit" that combines executor calls and message dispatching. <br><br>I should employ a `Mutex` to avoid concurrency issues, ensuring that `sendIntent` can’t run concurrently by locking across suspensions. Also, I’ll limit action emissions to the `Bootstrapper`, simplifying the interaction with the `Executor`. This approach should help keep everything running smoothly!**Final design outline**<br><br>I'm honing in on a design that preserves three components, but it might be less flexible than MVIKotlin. The core idea is to make sure that the `Bootstrapper` emits actions exclusively; the `Executor` will strictly handle messages. This means I won't need to include `dispatchAction` in the `ExecutorScope`, which simplifies things a lot.<br><br>Now, I’ll also design `sendAction` to be internal, allowing the bootstrapper to produce actions as needed without exposing them publicly. My focus will be on ensuring that messages generated during `executeIntent` and `executeAction` are processed before their respective functions return.<br><br>Furthermore, I need to maintain that only one intent or action is processed at a time while managing any potential concurrency that might arise. It feels good to think I have a plausible plan in place!**Considering Multiplatform Compatibility**<br><br>For a Kotlin Multiplatform project, I need to think about how to set up `CoroutineDispatcher`. Typically, I would use something like `MainScope()`, but for multiplatform development, I can't rely on `Dispatchers.Main` outside of UI. Instead, I’ll focus on injecting the appropriate dispatcher. It’s important to avoid creating a full library while still providing a practical skeleton.<br><br>I believe naming our store `CoroutineMviStore` or just `CoroutineStore` fits nicely, alongside defining an interface like `Store`. I’ll provide a note indicating that this is a simplified version meant for adaptation.<br><br>Additionally, I should guide the user on combining `Flow` with Compose, ensuring we adhere to the system's formatting, using Markdown headers and proper Kotlin code fencing.
  </details>
</div>

Below is a coroutine‑first design that:

- Keeps the 3 components: **Bootstrapper, Executor, Reducer**
- Keeps the 5 objects: **Intent, Action, Message, State, Label**
- Guarantees:
  - If you don’t manually switch dispatcher, when `sendIntent` / `sendAction` returns, all *synchronous* state changes are already applied.
  - If you don’t manually switch dispatcher, only one `Intent`/`Action` is processed at a time.

I’ll assume you’re fine with a `suspend` API (coroutine‑first) and `StateFlow`/`SharedFlow` instead of a custom reactive library.

At the end I’ll ask a couple of clarifying questions so we can refine it if needed.

---

## 1. Core concepts & type parameters

We’ll use 5 generic types on the store:

- `Intent` – from UI to store
- `Action` – from `Bootstrapper` to `Executor`
- `Message` – internal state change description
- `State` – UI state
- `Label` – one‑off events (navigation, toasts, etc.)

The public store API only exposes `Intent`, `State`, `Label`, like MVIKotlin.

```kotlin
package mvi

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Public-facing Store interface
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

    fun dispose()
}
```

---

## 2. Three components: Bootstrapper, Executor, Reducer

### 2.1 Bootstrapper

Bootstrapper is responsible for emitting `Action`s (initial, periodic, etc.). It’s fully coroutine‑based:

```kotlin
fun interface Bootstrapper<Action> {
    /**
     * Called once on store startup.
     * Use [dispatch] to send actions into the store.
     */
    suspend fun bootstrap(dispatch: suspend (Action) -> Unit)
}
```

Only the bootstrapper can emit `Action`s in this design (like MVIKotlin; the `Executor` only consumes them).

### 2.2 Reducer

Pure function: (State, Message) -> State.

```kotlin
fun interface Reducer<State, Message> {
    fun reduce(state: State, msg: Message): State
}
```

### 2.3 Executor + ExecutorScope

Executor processes `Intent` + `Action`, runs side‑effects, and emits `Message` and `Label`.

We give it an `ExecutorScope` that allows:

- reading current state
- `dispatch(Message)` → reduce to new state
- `publish(Label)` → push to label stream
- launching coroutines in the store’s scope

```kotlin
interface ExecutorScope<Intent, Action, State, Message, Label> {
    val state: State
    val coroutineScope: CoroutineScope

    suspend fun dispatch(message: Message)
    suspend fun publish(label: Label)
}

/**
 * Single Executor instance bound to a store instance.
 */
interface Executor<Intent, Action, State, Message, Label> {
    suspend fun executeIntent(
        intent: Intent,
        scope: ExecutorScope<Intent, Action, State, Message, Label>
    )

    suspend fun executeAction(
        action: Action,
        scope: ExecutorScope<Intent, Action, State, Message, Label>
    )
}
```

Both methods are `suspend`, so you can use coroutines directly inside the executor.

---

## 3. Coroutine store implementation

This is the core `CoroutineMviStore` clone that wires everything together using coroutines only.

Key points addressing your requirements:

1. **Sequential `Intent` / `Action` processing**  
   - `intentActionMutex` (a `Mutex`) is held across the whole `executeIntent` / `executeAction` call, including suspensions.
   - So *even if you call `delay()`* (without switching dispatcher) inside the executor, no other `Intent`/`Action` can start until it finishes.

2. **State updated before `sendIntent` / `sendAction` returns**  
   - `sendIntent`:
     - switches to the store dispatcher with `withContext(dispatcher)`.
     - acquires `intentActionMutex`.
     - calls `executor.executeIntent`.
   - `ExecutorScope.dispatch(message)`:
     - switches to the same dispatcher and takes `reduceMutex`.
     - reduces & writes `MutableStateFlow.value` synchronously.
   - Therefore any `dispatch(message)` done synchronously in `executeIntent` is completed before `executeIntent` returns, hence before `sendIntent` returns.

3. **No custom reactive library**  
   - Only `kotlinx.coroutines` (`StateFlow`, `SharedFlow`, `Mutex`, etc.).

### 3.1 Implementation

```kotlin
class CoroutineMviStore<Intent, Action, Message, State, Label>(
    initialState: State,
    private val bootstrapper: Bootstrapper<Action>?,
    private val executorFactory: () -> Executor<Intent, Action, State, Message, Label>,
    private val reducer: Reducer<State, Message>,

    /**
     * Dispatcher on which all store work is done.
     * On Android you can pass Dispatchers.Main.immediate.
     */
    private val dispatcher: CoroutineDispatcher
) : Store<Intent, State, Label> {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(dispatcher + job)

    // ------------- State & Labels -------------

    private val _state = MutableStateFlow(initialState)
    override val state: StateFlow<State> = _state.asStateFlow()

    private val _labels = MutableSharedFlow<Label>()
    override val labels: Flow<Label> = _labels.asSharedFlow()

    // ------------- Concurrency control -------------

    /**
     * Ensures only one Intent/Action is processed at a time
     * (executeIntent / executeAction).
     */
    private val intentActionMutex = Mutex()

    /**
     * Serializes reducer calls (Messages) even if multiple async
     * coroutines call dispatch() concurrently.
     */
    private val reduceMutex = Mutex()

    // ------------- Executor -------------

    private val executor: Executor<Intent, Action, State, Message, Label> =
        executorFactory()

    private inner class ExecutorScopeImpl :
        ExecutorScope<Intent, Action, State, Message, Label> {

        override val state: State
            get() = _state.value

        override val coroutineScope: CoroutineScope
            get() = scope

        override suspend fun dispatch(message: Message) {
            // All reduces are confined to the store dispatcher
            withContext(dispatcher) {
                reduceMutex.withLock {
                    val newState = reducer.reduce(_state.value, message)
                    _state.value = newState
                }
            }
        }

        override suspend fun publish(label: Label) {
            // Labels are emitted in the store scope
            _labels.emit(label)
        }
    }

    private val executorScopeImpl = ExecutorScopeImpl()

    // ------------- Bootstrapper -------------

    init {
        bootstrapper?.let { bs ->
            scope.launch {
                bs.bootstrap { action ->
                    // Same guarantees as for intents:
                    //  - runs on [dispatcher]
                    //  - serialized via [intentActionMutex]
                    sendAction(action)
                }
            }
        }
    }

    // ------------- Public API -------------

    override suspend fun sendIntent(intent: Intent) {
        // If caller is already on [dispatcher] and does not manually switch
        // to another dispatcher inside, this will behave synchronously.
        withContext(dispatcher) {
            intentActionMutex.withLock {
                executor.executeIntent(intent, executorScopeImpl)
            }
        }
        // When we reach here:
        //  - executeIntent has completed
        //  - all synchronous dispatch(message) calls are done
        //  => state is up-to-date for this intent
    }

    override fun dispose() {
        job.cancel()
    }

    // ------------- Internal: Actions -------------

    private suspend fun sendAction(action: Action) {
        withContext(dispatcher) {
            intentActionMutex.withLock {
                executor.executeAction(action, executorScopeImpl)
            }
        }
        // Same guarantees as sendIntent
    }
}
```

### Why this satisfies your guarantees

1. **“State is guaranteed already changed when sendIntent/sendAction returns”**

   - Any synchronous state change happens via `ExecutorScope.dispatch(message)`.
   - `dispatch`:
     - runs `reducer.reduce` and sets `_state.value` inside `reduceMutex.withLock` on store dispatcher.
   - `sendIntent`/`sendAction` only return after `executeIntent`/`executeAction` fully completes (including all `dispatch` calls executed in that call stack).
   - So if you don't explicitly do something like `scope.launch(anotherDispatcher) { dispatch(...) }`,
     then all state changes triggered by that intent/action are applied before `sendIntent`/`sendAction` return.

2. **“Only one intent/action is processed at a time”**

   - `intentActionMutex.withLock` wraps both `executeIntent` and `executeAction`.
   - `Mutex.withLock` is *suspension safe* – it holds the lock across suspensions like `delay()` (as long as you don't manually switch dispatcher inside).
   - So no two `executeIntent` or `executeAction` calls can overlap; they are strictly serialized in arrival order.

3. **Manual dispatcher switching opt‑out**

   If the library user does:

   ```kotlin
   scope.launch(Dispatchers.IO) {
       // or withContext(Dispatchers.IO) { ... }
       dispatch(message) // or something that leads to dispatch later
   }
   ```

   then:
   - `dispatch(message)` will still be serialized by `reduceMutex`, so state is safe.
   - But the *timing* may interleave with other intent/action processing because
     they opted out of the “no custom dispatcher” rule. This matches your requirement.

---

## 4. Example: simple counter store

A minimal example to show usage.

```kotlin
// ----- Model -----

sealed interface CounterIntent {
    object IncrementClicked : CounterIntent
    object DecrementClicked : CounterIntent
    object LoadInitial : CounterIntent
}

sealed interface CounterAction {
    object LoadFromRepository : CounterAction
}

sealed interface CounterMessage {
    data class SetCounter(val value: Int) : CounterMessage
    object Increment : CounterMessage
    object Decrement : CounterMessage
}

data class CounterState(
    val value: Int,
    val isLoading: Boolean
)

sealed interface CounterLabel {
    object ShowErrorToast : CounterLabel
}

// ----- Reducer -----

object CounterReducer : Reducer<CounterState, CounterMessage> {
    override fun reduce(state: CounterState, msg: CounterMessage): CounterState =
        when (msg) {
            is CounterMessage.SetCounter ->
                state.copy(value = msg.value, isLoading = false)
            CounterMessage.Increment ->
                state.copy(value = state.value + 1)
            CounterMessage.Decrement ->
                state.copy(value = state.value - 1)
        }
}

// ----- Executor -----

class CounterExecutor(
    private val repository: suspend () -> Int
) : Executor<CounterIntent, CounterAction, CounterState, CounterMessage, CounterLabel> {

    override suspend fun executeIntent(
        intent: CounterIntent,
        scope: ExecutorScope<CounterIntent, CounterAction, CounterState, CounterMessage, CounterLabel>
    ) {
        when (intent) {
            CounterIntent.IncrementClicked -> {
                scope.dispatch(CounterMessage.Increment)
            }
            CounterIntent.DecrementClicked -> {
                scope.dispatch(CounterMessage.Decrement)
            }
            CounterIntent.LoadInitial -> {
                // Delegate to Action
                // (in this simple design, only Bootstrapper creates actions,
                // but you could create actions here too if you want — then
                // you'd need explicit support for it)
            }
        }
    }

    override suspend fun executeAction(
        action: CounterAction,
        scope: ExecutorScope<CounterIntent, CounterAction, CounterState, CounterMessage, CounterLabel>
    ) {
        when (action) {
            CounterAction.LoadFromRepository -> {
                try {
                    scope.dispatch(
                        CounterMessage.SetCounter(
                            value = repository()
                        )
                    )
                } catch (t: Throwable) {
                    scope.publish(CounterLabel.ShowErrorToast)
                }
            }
        }
    }
}

// ----- Bootstrapper -----

class CounterBootstrapper : Bootstrapper<CounterAction> {
    override suspend fun bootstrap(dispatch: suspend (CounterAction) -> Unit) {
        // Fire an initial load action
        dispatch(CounterAction.LoadFromRepository)
    }
}

// ----- Store factory -----

fun createCounterStore(
    repository: suspend () -> Int
): Store<CounterIntent, CounterState, CounterLabel> =
    CoroutineMviStore(
        initialState = CounterState(value = 0, isLoading = true),
        bootstrapper = CounterBootstrapper(),
        executorFactory = { CounterExecutor(repository) },
        reducer = CounterReducer,
        dispatcher = Dispatchers.Main.immediate // or injected for KMP
    )
```

Usage from UI (e.g., Compose):

```kotlin
val store = remember { createCounterStore(repository) }
val state by store.state.collectAsState()

// onClick
LaunchedEffect(Unit) {
    store.sendIntent(CounterIntent.IncrementClicked)
}
```

Because of the guarantees:

- When `sendIntent` returns in that `LaunchedEffect` call, any synchronous state updates (like increment) are already visible in `state`.
- Intents are processed one at a time.

---

## 5. Optional: non‑suspending `accept` wrapper

If you want an API similar to MVIKotlin’s `accept`, you can add a non‑suspending wrapper that just launches:

```kotlin
fun <Intent, State, Label> Store<Intent, State, Label>.accept(
    intent: Intent,
    scope: CoroutineScope
) {
    scope.launch {
        sendIntent(intent)
    }
}
```

You keep the strong semantics in `sendIntent`, and `accept` is just “fire & forget”.

---

## 6. Clarifications

To refine this towards a closer MVIKotlin clone:

1. **Are you OK with `sendIntent` being `suspend`?**  
   Or do you want a non‑suspending, strictly synchronous `fun sendIntent(intent: Intent)` like MVIKotlin’s `accept`?

2. **Kotlin Multiplatform?**  
   - If yes, do you already have a way to provide a main dispatcher (e.g., expect/actual), or should the store be dispatcher‑agnostic with only injected dispatcher?

3. **Do you want the Executor to be able to emit `Action`s too, like a more advanced pipeline, or do you want to keep `Action`s strictly from the Bootstrapper (closer to MVIKotlin)?**

If you answer those, I can adjust the interfaces & implementation (e.g., add action dispatch from executor, add non‑suspending APIs, or make the dispatcher injection KMP‑friendly) and get you very close to a drop‑in MVIKotlin‑style clone.

---

## 🧑‍💻 User

answering your questions:

1. yes please, this is coroutine first
2. must be KMP, web/desktop/android/ios
3. yes, so that emitting action and trigger another action, I think MVIKotlin also support emitting action too

Let's also assume by default we use `Dispatcher.Main.immediate`, unless user override it

---

## 🤖 Assistant

<div style="border: 2px solid #dddddd; border-radius: 10px;">
  <details style="padding: 5px;">
    <summary>Deep reasoning</summary>
    **Analyzing MVIKotlin setup**<br><br>I'm exploring how to set up the Store interface in MVIKotlin. It involves dispatching an Intent and working with state and labels that are observable. I’m considering using event queues and thread confinement for proper state changes.<br><br>The user mentioned we should stick to KMP without additional reactive libraries, just using kotlinx.coroutines. It seems like the `Dispatchers.Main` should be available across all UI-supported targets. I'm thinking I should prepare to declare a default dispatcher or rely on injection when necessary.**Designing Dispatcher handling**<br><br>The user mentioned we should default to using `Dispatchers.Main.immediate`, unless overridden. I can define a `StoreDefaults` object with that dispatcher, but I need to ensure `Main` is available on the targeted UI environments, which seems okay per the user’s guidance.<br><br>Integrating KMP, I want to avoid platform-specific APIs and just use `kotlinx.coroutines`. I'm considering adding an `ExecutorScope.dispatchAction(action: Action)` to manage actions while ensuring that we process only one intent or action simultaneously. It’s crucial that all related updates occur under the same mutex for consistency.**Avoiding deadlocks in action sending**<br><br>I need to be cautious about potential deadlocks when using the same `sendAction` function that tries to acquire the same mutex. The simplest solution might be to make `sendAction` internal, ensuring it’s only called in contexts where the `intentActionMutex` is already held, like the bootstrapper and executor.<br><br>However, the bootstrapper should begin as a coroutine that might call `dispatchAction`, and it must maintain the sequential processing with UI-sent intents and actions. I'll create two versions of `emitAction`: one for the bootstrapper and another for the executor, ensuring we manage mutex locking properly to prevent re-entrancy issues.**Refining action processing semantics**<br><br>I'm outlining a structured approach to managing intent and action processing with a single logical "critical section" protected by `intentActionMutex`. For UI interactions, I’ll use `withContext(dispatcher) { intentActionMutex.withLock { processIntentAndActionChain(intent) } }`.<br><br>For bootstrapping, I’ll launch within the store scope, allowing the bootstrapper to feed actions into the same serialization pipeline. The method `sendActionFromBootstrapper` will mirror `sendIntent`, ensuring only one pipeline unit operates at any time.<br><br>I also need to ensure that the executor can emit actions without causing deadlocks, as Kotlin's Mutex is non-reentrant. I’m considering two potential solutions, starting with avoiding `sendAction` calls within the executor.**Managing action dispatch in ExecutorScope**<br><br>In the executor's `ExecutorScope`, we call `emitAction(action)` without reacquiring the lock. This queues the action for processing in the same critical section, ensuring synchronous pipeline management. I plan to treat actions as immediate calls by implementing `suspend fun dispatchAction(action: Action)`.<br><br>However, bootstrapped actions need to ensure serialization, so I’ll create a base `sendAction` method that acquires the lock. To avoid deadlocks, I’ll provide two pathways: one for the bootstrapper and another specifically for the executor that operates under existing locks. Sequential guarantees and message handling are crucial, especially with asynchronous dispatches from the executor. The semantics dictate only one intent or action is processed at a time without dispatcher switching, maintaining compatibility with MVIKotlin architecture.**Addressing concurrency in action processing**<br><br>I need to ensure that calling `launch` within the same dispatcher handles concurrent processing effectively, especially inside `dispatchAction`. Using `intentActionMutex` for all calls to `executeIntent` and `executeAction` is essential to prevent deadlocks from recursion. Although Kotlin’s Mutex isn’t reentrant, I’m considering implementing manual tracking for operation context.<br><br>We need to support asynchronous action processing while ensuring that state changes occur synchronously when `sendIntent` returns. User requirements show that `sendAction` should be accessible for manual dispatch, but we need to clarify how that integrates with the overall architecture while maintaining logical consistency.**Designing event handling in architecture**<br><br>The requirement isn't to expose action-sending directly from the view, but rather to maintain symmetry in design. I propose a public API comprising `suspend fun sendIntent(intent: Intent)` and optionally `suspend fun sendAction(action: Action)` for manual action injection. This provides clarity, as architectures often allow for external action sending.<br><br>To ensure correct behavior, `sendIntent` and `sendAction` should only process synchronously when called within the same dispatcher. If there’s ambiguity in the semantics, I should clarify with the user—specifically regarding how actions triggered from the executor interact with intent processing. I can also explore a design that supports sequential processing without Mutex, simplifying the architecture.**Handling event processing safely**<br><br>We need to choose a channel that allows for safe event processing—`Channel.UNLIMITED` or `Channel.BUFFERED` helps avoid deadlocks caused by sending from the consumer when waiting on acknowledgments. Immediately sending an event while awaiting acknowledgment can cause issues with nested events. Instead, I’ll treat actions triggered inside the executor as synchronous calls to `processAction`, but external actions need to be queued. We should differentiate between internal and external action emissions, and implement clear safeguards to avoid deadlocks, ensuring proper documentation for users to follow.**Designing event processing**<br><br>My plan is to use an actor event loop along with `Channel.UNLIMITED` to ensure sequential processing for top-level events while allowing nested actions to run inline. I'll introduce a sealed class `Event` to manage intents and actions. The main event loop will process incoming events, and I'll ensure the `ExecutorScope` carries out actions correctly without risking deadlocks, particularly when distinguishing between actions called from within or outside the event loop. We can use a marker in the context to handle this. The requirements suggest we need to clarify how dispatching actions works, especially with concurrency in mind.**Interpreting concurrency semantics**<br><br>I need to decide how to interpret "if user didn't manually switch dispatcher." I'll assume it means not launching unstructured coroutines outside the call stack, which aligns with "structured concurrency." I’ll clarify in the docs that guarantees apply only if users stick to suspending code in the executor. While they can launch with a new dispatcher and maintain sequential processing, manual dispatcher switching will exclude them from guarantees. Thus, we'll adopt a design using `Channel.UNLIMITED` for sequential event processing, while hiding Action and Message types in the `Store` interface to simplify the public API.**Defining store architecture**<br><br>I’m focusing on designing a `StoreFactory` like in MVIKotlin. I’ll include the necessary generics, but they don't need to influence the store's generics directly. While they might not specifically ask for a full `StoreFactory` design, providing cross-platform abstractions and a default store is valuable.<br><br>To simplify the API, I’ll ensure it assumes `Dispatchers.Main.immediate` by default. Then, I’ll clarify how actions and messages will be stored internally, and evaluate concurrency with a Mutex or event loop to maintain sequential processing, while avoiding data races on state changes.**Exploring concurrency and design**<br><br>I need to think about avoiding catastrophic concurrency while ensuring sequential actions. I’m considering wrapping `dispatchAction` in a mutex to maintain this, but that could lead to deadlocks. Since users may call `dispatchAction` from asynchronous coroutines, I’ll clarify in the documentation that this can result in unspecified behavior. <br><br>I’ll adopt a Mutex-based design with inline actions from the executor and document the importance of using `suspend` operations only inside the provided functions. The `Store` interface will reflect these changes and support the design principles laid out.**Refining ExecutorScope and constraints**<br><br>I'm streamlining the `ExecutorScope` design to include a `dispatchAction` method. In the `CoroutineMviStore`, I’m implementing this with an emphasis on using the same `ExecutorScope` instance for actions. The `processAction` method will facilitate both inline and top-level operations.<br><br>I need to ensure that we maintain sequential processing. To prevent concurrency risks, it’s crucial to restrict `dispatchAction` calls to within controlled contexts. I'll document the guidelines, advising users against launching asynchronous processes outside our structured concurrency model. Incorporating the default dispatcher, `Dispatchers.Main.immediate`, allows us to maintain multiplatform compatibility.**Refining Actions and State Management**<br><br>I'm focusing on how state reductions through `dispatch(Message)` use `reduceMutex` to ensure `_state.value` is safe from asynchronous messages. This guards against race conditions while acknowledging reordering could still happen.<br><br>Now, Guarantee 1 states that when `sendIntent` returns, all direct state changes from synchronous actions are complete. I need to revise the `dispatchAction` method to call `processAction` directly and ensure that it does not call `withContext(dispatcher)` inside, matching the context of where it's invoked. I'll also implement `processIntent` for `sendIntent`. <br><br>I must ensure that the code maintains sequential semantics while considering potential recursion when actions trigger further dispatches. The goal is to ensure clarity on invariants, especially how we handle the `Dispatchers.Main.immediate` default argument. I’ll present the code structure clearly, abiding by all guidelines while keeping everything concise for easy understanding.**Ensuring Safe Dispatcher Usage**<br><br>I'm thinking about how to handle state updates while keeping everything confined to the main dispatcher. If any external calls switch the dispatcher, I must ensure state consistency.<br><br>For `ExecutorScopeImpl.publish`, it might be best to utilize `withContext(dispatcher)` to keep label emissions consistent, even if it’s not strictly necessary.<br><br>When implementing `dispatchAction`, I should also wrap it in `withContext(dispatcher)` to ensure actions run on the main dispatcher, regardless of how they were invoked. This maintains the integrity of the process by sequentializing actions and intents under the correct mutex. <br><br>The goal is to avoid blocking the main thread while still managing concurrency safely. I'll verify that no concurrent processes interfere with state updates. If any asynchronous tasks run concurrently, the `reduceMutex` will safeguard state integrity, ensuring all sequences remain valid.**Adjusting for Asynchronous Code**<br><br>I need to consider how to handle asynchronous code in the executor. If `scope.coroutineScope.launch(Dispatchers.IO)` is used to load data, it’ll dispatch messages to the main thread with `withContext(dispatcher)`, ensuring state updates happen after the data is loaded.<br><br>Even though the pipeline may end and the `intentActionMutex` might be free, new intents from the UI can still be processed. The `dispatch` utilizes `reduceMutex` to keep state updates sequential, which helps maintain robustness against interleaving.<br><br>Now I’m focusing on enhancing our architecture to accommodate the latest generics and actions, ensuring everything works seamlessly across different platforms like KMP. I’ll organize the answer by creating clear sections about core interfaces, the CoroutineStore implementation, and a simple usage example. It’s crucial to include proper `import` statements for a standalone code snippet.
  </details>
</div>

Below is a coroutine‑first, KMP‑friendly “MVIKotlin‑like” design that:

- Keeps the 3 components: **Bootstrapper, Executor, Reducer**
- Keeps the 5 objects: **Intent, Action, Message, State, Label**
- Guarantees (under clear conditions):
  - If you call `sendIntent` / `sendAction` and you **don’t** break the model by manually switching dispatchers in weird ways inside the store, then when they return, all **synchronous** state updates from that intent/action (including nested actions) are already applied.
  - If you don’t manually break the model, only **one** `Intent`/`Action` chain is processed at a time.
- Default dispatcher is `Dispatchers.Main.immediate` (overridable), and everything is KMP (only uses `kotlinx.coroutines`).

> Important usage rule to keep these guarantees:  
> Inside `Executor` and `Bootstrapper`, write **suspending** code (just `suspend` functions, `delay`, etc.).  
> If you start “detached” coroutines that later call back into `dispatchAction`/`sendIntent`/`sendAction`, you’re explicitly stepping outside the strict guarantees (same as in MVIKotlin when you start messing with schedulers).

---

## 1. Core interfaces (commonMain, KMP)

These can live in `commonMain` of a KMP module.

```kotlin
package mvi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Public-facing Store.
 *
 * - Intent: from UI to store
 * - Action: internal/external triggers (from Bootstrapper, Executor, or even UI if you want)
 * - State: UI state
 * - Label: one-off events (navigation, toasts, etc.)
 */
interface Store<Intent, Action, State, Label> {
    val state: StateFlow<State>
    val labels: Flow<Label>

    /**
     * Main entry point for UI.
     */
    suspend fun sendIntent(intent: Intent)

    /**
     * Optional entry point for external actions.
     * (Most of the time you’ll only use Intents from UI.)
     */
    suspend fun sendAction(action: Action)

    fun dispose()
}

/**
 * Bootstrapper produces Actions when the store starts (or over time).
 */
fun interface Bootstrapper<Action> {
    suspend fun bootstrap(dispatchAction: suspend (Action) -> Unit)
}

/**
 * Pure reducer: (State, Message) -> State
 */
fun interface Reducer<State, Message> {
    fun reduce(state: State, msg: Message): State
}

/**
 * Scope visible to Executor.
 * Lets the Executor:
 *  - read current state
 *  - dispatch Messages (state changes)
 *  - publish Labels (one-off events)
 *  - dispatch Actions (chained internal pipelines)
 *  - launch additional work on store's CoroutineScope if desired
 */
interface ExecutorScope<Intent, Action, State, Message, Label> {
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
    suspend fun executeIntent(
        intent: Intent,
        scope: ExecutorScope<Intent, Action, State, Message, Label>
    )

    suspend fun executeAction(
        action: Action,
        scope: ExecutorScope<Intent, Action, State, Message, Label>
    )
}
```

This is directly analogous to MVIKotlin, just coroutine‑first and without any Rx‑like abstraction.

---

## 2. Coroutine store implementation (commonMain, KMP)

This is your “MVIKotlin clone” implementation backed purely by coroutines.

```kotlin
package mvi

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coroutine-first Store implementation.
 *
 * All store logic runs on [dispatcher], which defaults to Dispatchers.Main.immediate
 * (on platforms where it's available).
 */
class CoroutineStore<Intent, Action, Message, State, Label>(
    initialState: State,
    private val bootstrapper: Bootstrapper<Action>?,
    private val executorFactory: () -> Executor<Intent, Action, State, Message, Label>,
    private val reducer: Reducer<State, Message>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) : Store<Intent, Action, State, Label> {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(dispatcher + job)

    private val _state = MutableStateFlow(initialState)
    override val state: StateFlow<State> = _state.asStateFlow()

    private val _labels = MutableSharedFlow<Label>()
    override val labels = _labels

    // Ensures that only one Intent/Action "chain" executes at a time.
    private val intentActionMutex = Mutex()

    // Serializes reducer calls even if messages are emitted from async coroutines.
    private val reduceMutex = Mutex()

    private val executor: Executor<Intent, Action, State, Message, Label> = executorFactory()

    private inner class ExecutorScopeImpl :
        ExecutorScope<Intent, Action, State, Message, Label> {

        override val state: State
            get() = _state.value

        override val coroutineScope: CoroutineScope
            get() = scope

        override suspend fun dispatch(message: Message) {
            // Always reduce on [dispatcher] in a serialized manner.
            withContext(dispatcher) {
                reduceMutex.withLock {
                    val newState = reducer.reduce(_state.value, message)
                    _state.value = newState
                }
            }
        }

        override suspend fun publish(label: Label) {
            // Labels are also emitted on [dispatcher] for consistency.
            withContext(dispatcher) {
                _labels.emit(label)
            }
        }

        override suspend fun dispatchAction(action: Action) {
            // Nested action processing.
            //
            // If this is called as part of an active Intent/Action chain
            // (normal case; no manual weirdness), we are already under
            // [intentActionMutex] and on [dispatcher].
            //
            // We still force [dispatcher] just in case user switched context.
            withContext(dispatcher) {
                processAction(action)
            }
        }
    }

    private val executorScopeImpl = ExecutorScopeImpl()

    init {
        // Run bootstrapper once the store is created.
        bootstrapper?.let { bs ->
            scope.launch {
                bs.bootstrap { action ->
                    // Bootstrapper actions go through the same pipeline as external actions.
                    sendAction(action)
                }
            }
        }
    }

    // ----------------- Public API -----------------

    override suspend fun sendIntent(intent: Intent) {
        // All Intents are funneled through [dispatcher] and protected by [intentActionMutex].
        // This guarantees:
        //  - At most one Intent/Action chain at a time.
        //  - When this function returns, all synchronous state updates
        //    (including any nested Actions triggered synchronously)
        //    are already applied.
        withContext(dispatcher) {
            intentActionMutex.withLock {
                processIntent(intent)
            }
        }
    }

    override suspend fun sendAction(action: Action) {
        // Same guarantees as sendIntent, but for top-level Actions.
        withContext(dispatcher) {
            intentActionMutex.withLock {
                processAction(action)
            }
        }
    }

    override fun dispose() {
        job.cancel()
    }

    // ----------------- Internal processing -----------------

    private suspend fun processIntent(intent: Intent) {
        executor.executeIntent(intent, executorScopeImpl)
    }

    private suspend fun processAction(action: Action) {
        executor.executeAction(action, executorScopeImpl)
    }
}
```

### Why this satisfies your 2 key requirements

1. **“when sendIntent/sendAction returns, state is guaranteed already changed”**

   Under these conditions:

   - You call `sendIntent`/`sendAction` (suspending) from **any** coroutine.
   - Inside `Executor.executeIntent` / `executeAction` you:
     - Only use suspending code (`suspend fun`s, `delay`, etc.) **OR**
     - If you *do* create child coroutines, they don’t call back into `sendIntent`/`sendAction`/`dispatchAction` until after the current call finishes.

   Then:

   - Both `sendIntent` and `sendAction`:
     - Switch to the store `dispatcher` using `withContext(dispatcher)`.
     - Acquire `intentActionMutex`.
     - Call `processIntent` / `processAction`, which calls into the `Executor`.
   - Any synchronous `dispatch(message)` inside executor:
     - Runs `reducer.reduce` and updates `_state.value` immediately (guarded by `reduceMutex`).
   - Any synchronous `dispatchAction(action)` inside executor:
     - Calls `processAction(action)` inline, which in turn calls `executeAction` and its own `dispatch(...)`’s **before** the outer `sendIntent`/`sendAction` returns.

   Therefore, when `sendIntent` / `sendAction` finish:

   - The entire **synchronous pipeline** (Intent → Executor → Messages → State, plus chained Actions triggered synchronously) has been applied.

   As in MVIKotlin, if you explicitly break this by starting your own concurrent coroutines that later call back into the store, you step outside this guarantee.

2. **“only one intent/action is processed at a time”**

   - All **top‑level** Intents and Actions go through `intentActionMutex.withLock { ... }`.
   - That mutex is held for the whole chain: `executeIntent` → any nested `dispatchAction` → `executeAction` → any nested `dispatchAction` and so on.
   - As long as you don’t start parallel coroutines that call back into `sendIntent`/`sendAction`/`dispatchAction` concurrently, there is at most **one** such chain running at any time, on a single dispatcher.

   Again, if you deliberately launch parallel background jobs that call back into the store, you’re outside the “one at a time” contract (same concept as messing with Rx schedulers in MVIKotlin).

---

## 3. Example usage (KMP‑friendly)

Minimal example with all 5 types: Intent, Action, Message, State, Label.

```kotlin
// ----- Model -----

sealed interface CounterIntent {
    object IncrementClicked : CounterIntent
    object DecrementClicked : CounterIntent
    object RefreshClicked : CounterIntent
}

sealed interface CounterAction {
    object LoadFromRepository : CounterAction
}

sealed interface CounterMessage {
    object Loading : CounterMessage
    data class Loaded(val value: Int) : CounterMessage
    object Increment : CounterMessage
    object Decrement : CounterMessage
}

data class CounterState(
    val value: Int = 0,
    val isLoading: Boolean = false
)

sealed interface CounterLabel {
    object ShowError : CounterLabel
}

// ----- Reducer -----

object CounterReducer : Reducer<CounterState, CounterMessage> {
    override fun reduce(state: CounterState, msg: CounterMessage): CounterState =
        when (msg) {
            CounterMessage.Loading ->
                state.copy(isLoading = true)
            is CounterMessage.Loaded ->
                state.copy(value = msg.value, isLoading = false)
            CounterMessage.Increment ->
                state.copy(value = state.value + 1)
            CounterMessage.Decrement ->
                state.copy(value = state.value - 1)
        }
}

// ----- Executor -----

class CounterExecutor(
    private val loadFromRepo: suspend () -> Int
) : Executor<CounterIntent, CounterAction, CounterState, CounterMessage, CounterLabel> {

    override suspend fun executeIntent(
        intent: CounterIntent,
        scope: ExecutorScope<CounterIntent, CounterAction, CounterState, CounterMessage, CounterLabel>
    ) {
        when (intent) {
            CounterIntent.IncrementClicked -> {
                scope.dispatch(CounterMessage.Increment)
            }
            CounterIntent.DecrementClicked -> {
                scope.dispatch(CounterMessage.Decrement)
            }
            CounterIntent.RefreshClicked -> {
                // Use Actions as a pipeline
                scope.dispatchAction(CounterAction.LoadFromRepository)
            }
        }
    }

    override suspend fun executeAction(
        action: CounterAction,
        scope: ExecutorScope<CounterIntent, CounterAction, CounterState, CounterMessage, CounterLabel>
    ) {
        when (action) {
            CounterAction.LoadFromRepository -> {
                scope.dispatch(CounterMessage.Loading)
                try {
                    // This can suspend (network/DB/etc.)
                    val value = loadFromRepo()
                    scope.dispatch(CounterMessage.Loaded(value))
                } catch (t: Throwable) {
                    scope.publish(CounterLabel.ShowError)
                }
            }
        }
    }
}

// ----- Bootstrapper -----

class CounterBootstrapper : Bootstrapper<CounterAction> {
    override suspend fun bootstrap(dispatchAction: suspend (CounterAction) -> Unit) {
        // Trigger an initial load when the store starts.
        dispatchAction(CounterAction.LoadFromRepository)
    }
}

// ----- Store factory -----

fun createCounterStore(
    loadFromRepo: suspend () -> Int,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
): Store<CounterIntent, CounterAction, CounterState, CounterLabel> =
    CoroutineStore(
        initialState = CounterState(),
        bootstrapper = CounterBootstrapper(),
        executorFactory = { CounterExecutor(loadFromRepo) },
        reducer = CounterReducer,
        dispatcher = dispatcher
    )
```

Usage from Compose (Android / Desktop / Web):

```kotlin
@Composable
fun CounterScreen(store: Store<CounterIntent, CounterAction, CounterState, CounterLabel>) {
    val state by store.state.collectAsState()

    // Observe labels
    LaunchedEffect(store) {
        store.labels.collect { label ->
            when (label) {
                CounterLabel.ShowError -> {
                    // show toast / snackbar / etc.
                }
            }
        }
    }

    // onClick handlers:
    // rememberCoroutineScope or viewModelScope, etc.
    val scope = rememberCoroutineScope()

    Button(onClick = { scope.launch { store.sendIntent(CounterIntent.IncrementClicked) } }) {
        Text("+")
    }
}
```

---

## 4. Summary of behavior vs your requirements

- **3 components**:
  - `Bootstrapper<Action>`
  - `Executor<Intent, Action, State, Message, Label>`
  - `Reducer<State, Message>`

- **5 objects**:
  - `Intent` (from UI)
  - `Action` (internal/external triggers, from Bootstrapper or Executor, and optionally from UI)
  - `Message` (internal state-change events)
  - `State` (UI model)
  - `Label` (one-off events)

- **Guarantees** (under “don’t manually break the model” conditions):
  - When `sendIntent` / `sendAction` return:
    - All synchronous `dispatch(Message)` and nested `dispatchAction(Action)` in that chain are fully applied to `state`.
  - Only one Intent/Action **chain** is processed at a time, thanks to `intentActionMutex`.

If you want, next step I can:

- Add a small `StoreFactory` builder that mirrors MVIKotlin’s factory API.
- Or tighten/relax the rules around `dispatchAction` from background coroutines if you plan to do more aggressive parallelism.

---

## 🧑‍💻 User

I don't think it is a good idea to expose `suspend fun sendAction(action: Action)` in `Store` as public API, only bootstrapper and executor itself can send action.

Also, for the store, let's have an `autoInit: Boolean` flag set to true by default, where when it is false, the user would need to manually call`init()` to start the store, this is similar to MVIKotlin as well

---

## 🤖 Assistant

<div style="border: 2px solid #dddddd; border-radius: 10px;">
  <details style="padding: 5px;">
    <summary>Deep reasoning</summary>
    **Updating design considerations**<br><br>I need to adjust the design based on a few key points. The public Store shouldn't expose the sendAction; only the Bootstrapper and Executor should send actions. I should add an autoInit flag, defaulting to true, which requires users to manually call init() if set to false, similar to MVIKotlin.<br><br>I also need to follow the developer's guidance on using proper Markdown headings and code blocks. There’s a clarification to confirm about the idempotency of calling init(), and I’ll make sure only the bootstrapper and executor can access action injection. So, I'll remove the public sendAction from the Store interface and keep the internal or limited access instead.**Adjusting executor functions**<br><br>For the executor, I've included the dispatchAction method within the ExecutorScope, which is great! I also added support for nested actions using Mutex to ensure only one chain runs at a time. We need to change the bootstrapper's access to sendAction from public to an internal store function to keep it secure. <br><br>In addition, I’ll adjust the init function to call `enqueueAction` or directly call `processAction` with a lock to maintain chain semantics. I need to include an `init()` function in the Store, managing the autoInit flag in the constructor. If set to true, we’ll start the bootstrapper; otherwise, the user must initiate it. I also need to ensure multiple init calls are safe, using an AtomicBoolean to track if it has started.**Addressing atomic usage in KMP**<br><br>In Kotlin Multiplatform (KMP), I have to decide on atomic usage, whether to use `kotlin.concurrent.AtomicReference` or `kotlinx.atomicfu`. I’ll probably lean towards `kotlinx.atomicfu`, but maintaining a Mutex-protected boolean could also work. <br><br>I could implement a boolean `started` protected by `startMutex` to set its state and run the bootstrapper accordingly. The alternative is to use `val started = atomic(false)`, but if I want minimal dependencies, sticking with Mutex might be best. I should also guard `init()` with `startMutex` to avoid concurrency issues across targets.**Managing initialization in store**<br><br>To handle initialization in the single store scope with the dispatcher, I’ll use `withContext(dispatcher)` to check the `started` value. If it’s not marked as true, I’ll set it and start the bootstrapper. <br><br>I need to allow for lazy starting of the store to handle drop events, so I’ll implement `autoInit`. If the user calls sendIntent before init, I have to decide if we should auto-init or throw an error. In MVIKotlin, the store is cold until started, so it might be best to require `init()` explicitly to avoid confusion.**Clarifying init and sendIntent behavior**<br><br>The user suggests that they must manually call `init()` when autoInit is set to false. This raises the question of whether `sendIntent` should still process even if the store isn’t started. In MVIKotlin, the store can accept intents before it officially starts, but the bootstrapper isn’t invoked.<br><br>I need to clarify whether intents before initialization should be processed or throw an error. It seems to make sense that we could queue intents to process them after starting, but I should confirm. Maybe we can propose that `sendIntent` triggers `init()` if not started, but they stated the user needs to call `init()` manually when autoInit is false, so we likely shouldn't auto-start on `sendIntent`.**Proposing design for init and sendIntent**<br><br>I think I should propose a design where, when `autoInit` is false, the store won't start until `init()` is called. However, `sendIntent` will still process using the same pipeline even if `started` is false. The start mainly affects the bootstrapper, so I can make `init` focus solely on starting the bootstrapper without limiting intent processing. This aligns with the requirement to manually call `init()`. <br><br>In MVIKotlin, the bootstrapper typically triggers after store creation, but they do have lazy bootstrapper starts. I should ask whether `init` only triggers the bootstrapper or if it also gates `sendIntent`. Also, I'll remove `sendAction` from the Store interface as planned.**Implementing dispatchAction in ExecutorScope**<br><br>I need to provide `dispatchAction` in the ExecutorScope and the dispatch function as a parameter in Bootstrapper. I must also ensure that nested `dispatchAction` calls inside the executor don't lead to deadlocks since Mutex is not reentrant. <br><br>Previously, `dispatchAction` was used with `withContext(dispatcher) { processAction(action) }`, but it didn't reacquire the mutex while already inside `intentActionMutex`. If `dispatchAction` is invoked from a locked region, it won’t reacquire the mutex, but calling it from a different coroutine context should ensure serialization. However, serialization depends on maintaining the same dispatcher, as calls may come from a child store scope and potentially run concurrently. The `reduceMutex` will help with state serialization, but not with maintaining a single chain.**Supporting external actions in the chain**<br><br>I want to ensure that actions from outside can be supported while keeping the bootstrapper and executor responsible for calling them. The bootstrapper will route through `sendAction`, which uses a mutex. For the executor's `dispatchAction`, I need to preserve the chain. <br><br>Here’s my proposed method: <br><br>I’ll create an internal function, `processActionChain(action)`, that doesn’t lock and can be used within locked regions. Then, I’ll add `sendActionExternal(action)` to acquire the mutex and call `processActionChain`. <br><br>Since Kotlin's Mutex isn’t reentrant and doesn’t support a check for current ownership, I’ll introduce a CoroutineContext element called `ChainContext` as a boolean. When calling `sendIntent` or `sendActionExternal`, I’ll add this context via `withContext(storeChainCtx)`. If `ChainContext` is present in `ExecutorScope.dispatchAction`, we can call `processActionChain()` directly; otherwise, we’ll call `sendActionExternal`, ensuring we still run on the appropriate dispatcher.**Handling dispatcher switching and context**<br><br>I need to address the precondition that guarantees if the user didn’t switch the dispatcher. To manage any potential switching, I can still leverage `withContext`. <br><br>However, since I can’t reliably check mutex ownership, I’ll create a context element to indicate when we’re in an active chain. This would look like:<br><br>```<br>object InChainKey : CoroutineContext.Key<br>class InChain : AbstractCoroutineContextElement(InChainKey)<br>```<br><br>In `sendIntent` and `sendActionExternal`, I’ll use `withContext(InChain()) { intentActionMutex.withLock { ... } }`, making sure the `InChain` element is within the lock. Since there could be nested calls, I’ll need to set the context inside after locking to ensure visibility for nested calls. <br><br>The plan will be to implement `withContext(dispatcher) { intentActionMutex.withLock { withContext(InChain()) { ... } } }` so the inner layers correctly recognize the `InChain`.**Implementing dispatchAction logic**<br><br>For `ExecutorScope.dispatchAction`, I’ll check if the coroutine context contains `InChainKey`. If it does, I’ll call `processAction(action)` directly. If it doesn’t, I'll use a fallback that runs within a `withContext(dispatcher)`, locking with `intentActionMutex`. <br><br>Now, if I always call `withContext(dispatcher) { processAction(action) }`, it won’t lock and could lead to concurrent execution, which might disrupt the "one chain at a time" rule. So, I’ll stick with the context marker approach.<br><br>Moreover, I’ll ensure that label emissions and state reductions keep their intended semantics intact. It’s also crucial to default to `Dispatchers.Main.immediate` across KMP, remembering that on iOS and native platforms, `Main.immediate` is available with coroutines versions 1.6 or 1.7. However, Kotlin/JS doesn't support `main immediate`. As a KMP, we do have `Dispatchers.Main`, so if it isn’t available, I can ask the user to set a dispatcher. I’ll also implement an expect/actual fallback for smooth operation!**Clarifying dispatcher and initialization**<br><br>I’m looking at the use of `Dispatchers.Main.immediate` in the code. For Kotlin/JS, it seems that immediate is only available in coroutines 1.8, so I need to ensure compatibility. I’ll create a fallback using the `defaultDispatcher()` function to provide `Main.immediate` if available, defaulting to `Main` or `Unconfined` otherwise.<br><br>Next, I’ll introduce an external function, `defaultStoreDispatcher()`, specifically tailored for each platform, using expected and actual declarations. I’ll make sure to keep things straightforward, allowing the code to work with `Dispatchers.Main.immediate` while implementing this fallback.<br><br>Regarding `autoInit`, I’ll add it as a constructor parameter with a default value of true and implement the `init()` method to automatically call start if `autoInit` is true. This will ensure it triggers the bootstrapper when needed and considers starting cold flows since StateFlow is available!**Modifying initialization and action flow**<br><br>I see that we previously had an `init` function starting the bootstrapper automatically. I plan to change this to a `startIfNeeded()` function that will be called during creation and when `init()` is invoked, but only if `autoInit` is true.<br><br>I'll clarify that `sendIntent` won't implicitly call `init()` since the user specified that a manual call is necessary if `autoInit` is false. However, `sendIntent` will still work, even if the bootstrapper isn't started. Maybe they want it to resemble MVIKotlin behavior, so I’ll ask for their clarification on whether they want `sendIntent` to function before initialization.<br><br>Regarding action sending, I’ll also enforce the restriction that only the bootstrapper and executor can send actions.**Addressing nested dispatch action flow**<br><br>I need to ensure that nested `dispatchAction` calls don't lead to deadlocks since we're using non-reentrant locks. To handle edge cases, if a user calls `dispatchAction` within the executor while using `withContext(Dispatchers.IO)`, it could complicate things when returning to the store.<br><br>My plan is to make `dispatchAction` check for the presence of the `InChain` context. If they switched contexts, the chain would be lost, leading to a lock reacquisition, which could break the "one at a time" rule due to attempts to lock while already holding.**Clarifying dispatcher switch behavior**<br><br>I've been considering the heuristics regarding dispatcher switching within the same call chain. If the dispatcher is switched, while still holding the lock, the context element can be retained across the `withContext` call. In Kotlin, `withContext` maintains the coroutine context during a context switch, which means that if we switch to `Dispatchers.IO`, our `InChain` context can remain intact.<br><br>The existing elements aren't removed unless they're specifically replaced. Since I'm only adding a new dispatcher, the `InChain` context is still present. So, the check for `coroutineContext[InChainKey] != null` remains valid even after switching. However, there's a note about what happens if the user didn't manually switch the dispatcher.**Evaluating context handling and mutex behavior**<br><br>Our system might still carry the `InChain` context, and while disclaimers apply, we want to maintain safe semantics. If the dispatcher is switched to `Dispatchers.IO`, it continues to carry the context element, allowing us to treat it as still being in the chain and call `processAction` without a lock. <br><br>While we're no longer on the dispatcher, `processAction` utilizes `executor.executeAction`, which may call dispatch using `withContext(dispatcher)`. This respects the "one chain at a time" property since the `intentActionMutex` wasn't released. Even if `dispatchAction` chooses to bypass locking, the mutex remains held by `sendIntent`. It's also worth noting that mutexes aren't tied to specific threads—they work with suspend semantics, so we rely on the outer lock. This all seems to be good!**Implementing lock management and context**<br><br>The `IntentActionMutex` in Kotlin isn't reentrant, but nested calls inside a locked region can proceed without reacquiring locks, which is good to know! So, I'll implement an `InChain` object as a coroutine context element. <br><br>Instead of creating a new instance each time, I might define it as a singleton instance. It's essential to add `withContext(inChain)` only while holding the lock. A potential concern arises because the `sendAction` of the bootstrapper will call within a lock, so we should pass a `dispatchAction` function to the bootstrapper that calls `sendActionExternal` with the necessary locking. <br><br>Also, I'll handle cases where the store may be disposed by checking if `job.isActive` before allowing further calls. If `sendIntent` is called after disposal, it should either throw a cancellation or be ignored. This seems like a solid plan!**Refining context and job handling**<br><br>I’m considering how to handle the context and job cancellations more effectively. The `withContext(dispatcher)` uses the same scope, so if a job is canceled, it seems that `withContext` may still run since it only utilizes the dispatcher. That's a problem, as I don't want operations tied to an earlier state to inadvertently execute after disposal.<br><br>To avoid this, I think it's better to use `scope.coroutineContext`, which ensures that everything is confined to the job. It also supports hierarchical cancellation. So, I’ll revise all instances to use `withContext(scope.coroutineContext)` for consistency. This way, we can manage cancellations properly while keeping everything organized. It's a solid plan moving forward!**Ensuring code functionality and structure**<br><br>I need to make sure the code compiles properly, and it looks like we're good with the `scope` being a val. Now, it's important to manage the `MutableSharedFlow` with an appropriate buffer to prevent lost emissions. We’ll keep the default as no replay, but we also need to keep in mind the UI consumption for iOS.<br><br>It’s time to outline the final code: <br><br>- I’ll make sure to update the Store interface to include an `init()` function.<br>- The store implementation will have an `autoInit` flag and separate the `startIfNeeded()` function.<br>- I’ll provide the `Bootstrapper` interface and the `ExecutorScope` with `dispatchAction`.<br>- Additionally, I’ll define the `Executor` interface and the `Reducer`.<br>- I’ll include the use of `InChainContext` and ensure we have a mechanism for default dispatcher handling with suggested platform-specific actuals pointing to `Dispatchers.Main.immediate` for KMP. <br><br>Let’s move ahead with this structured approach!**Implementing dispatch action mechanics**<br><br>Since the user emphasized using the default `Dispatchers.Main.immediate`, I’ll stick with that approach. For scenarios like JS/Native where `immediate` may not exist, I’ll provide an optional `expect/actual` snippet for adaptation. <br><br>For the `defaultDispatcher` function, I’ll configure it to use `Dispatchers.Main.immediate` and fall back to `Dispatchers.Main` if `immediate` isn’t available. I think it’s best to keep the default injection parameter simple without adding unnecessary complexity.<br><br>For the implementation in `ExecutorScopeImpl.dispatchAction`, I’ll ensure to check if `coroutineContext[InChain]` is available and proceed accordingly. The `processActionChain` function will involve calling `executor.executeAction(action, executorScopeImpl)`, so I’ll make sure to import `kotlin.coroutines.coroutineContext` as needed. This structure should give us the reliability we’re after!**Ensuring dispatcher execution**<br><br>I want to make sure that everything executes on the correct store dispatcher. To do this, I’ll start by calling `withContext(scope.coroutineContext)` at the beginning of my event flows, so it runs on the dispatcher as intended. If `processActionChain` is called within a locked region triggered by `sendIntent`, and it’s already using `withContext(scope.coroutineContext)`, then we're good to go.<br><br>Still, for extra safety — in case we end up with a direct call to `dispatchAction` — I will keep that check in place. Maybe adding `requireOnDispatcher()` is a consideration, but I’m not sure if it’s strictly necessary. Keeping safety in mind feels like the right move!**Adjusting the ExecutorScopeImpl**<br><br>I need to update the `ExecutorScopeImpl.publish` method to use `scope.launch { _labels.emit(label) }`, or directly call `emit` with `withContext`. I'll opt for `withContext(scope.coroutineContext) { _labels.emit(label) }` to ensure it runs correctly under the intended context.<br><br>For bootstrapping, I'll add the `startIfNeeded` function, structured to launch the bootstrapper with `scope.launch { bootstrapper.bootstrap { dispatch -&gt; // Here we won't call dispatchAction directly. Instead, we'll use 'sendActionExternal(action)' } }`.<br><br>In `sendActionExternal`, I'll make sure it uses the proper context with a mutex lock to maintain order. However, since we can't call suspend functions from `scope.launch`, we'll pass a suspend function as `dispatchAction: suspend (Action) -&gt; Unit` in `bootstrapper.bootstrap`, which should work fine. I also want to ensure that `init()` only returns after the bootstrapper has completed — it runs asynchronously in MVIKotlin, so the bootstrapping may take a moment to dispatch actions. Sounds good!**Preparing the Final Code Overview**<br><br>I need to clarify that while users might expect the `init()` call to be synchronous, I'll mention its semantics. If `autoInit` is true, it should start automatically. I’ll provide updated usage examples and include a caution about potential recursive calls to `dispatchAction`, which can result in deep loops.<br><br>Now, I’ll craft the final answer, including:<br><br>- A title.<br>- A summary bullet point list of the changes implemented.<br>- The actual code, detailing:<br>  - Interfaces<br>  - Store implementation<br>  - An example of usage<br>  - Optional `expect/actual` for the dispatcher<br>  - A test guarantee demonstration, possibly including a unit test pattern.<br><br>For clarification, I’ll present a couple of questions at the end: one about `init` semantics for intents pre-init—should they be allowed? I think they should, so I’ll propose default behavior and ask for confirmation. Lastly, I’ll keep it clean with Markdown headings and code blocks labeled `kotlin`. Sounds like a plan!
  </details>
</div>

Great—here’s a refined, coroutine‑first, KMP‑friendly design that:

- Preserves Bootstrapper, Executor, Reducer and the five objects: Intent, Action, Message, State, Label.
- Does NOT expose sendAction on the public Store (only Bootstrapper and Executor can dispatch actions).
- Adds autoInit: Boolean = true and a manual init() that starts the store (bootstrapper) when autoInit = false.
- Defaults to Dispatchers.Main.immediate unless overridden.
- Keeps guarantees:
  - If the user doesn’t manually break the model (e.g., unstructured parallelism), when sendIntent/dispatchAction return, synchronous state updates in that chain are already applied.
  - Only one intent/action chain is processed at a time.

Key detail to avoid deadlocks while supporting nested actions:

- A “chain marker” CoroutineContext element marks execution that is already inside the intent/action critical section. ExecutorScope.dispatchAction uses it to decide whether to inline (no re-lock) or acquire the mutex (for external/late calls).

Design overview

- Public API: Store exposes state, labels, sendIntent, init(), dispose(). No public sendAction.
- Bootstrapper gets a suspend dispatchAction lambda.
- Executor gets an ExecutorScope with dispatch(Message), publish(Label), dispatchAction(Action).
- Store has autoInit; if true, bootstrapper starts automatically; if false, you must call init().

Core interfaces (commonMain)

```kotlin
package mvi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface Store<Intent, State, Label> {
    val state: StateFlow<State>
    val labels: Flow<Label>

    suspend fun sendIntent(intent: Intent)

    /**
     * When autoInit = false, you must call init() to start the store (bootstrapper).
     * Idempotent: calling multiple times is safe.
     */
    fun init()

    fun dispose()
}

fun interface Bootstrapper<Action> {
    suspend fun bootstrap(dispatchAction: suspend (Action) -> Unit)
}

fun interface Reducer<State, Message> {
    fun reduce(state: State, msg: Message): State
}

/**
 * Scope available to the Executor during processing.
 */
interface ExecutorScope<Intent, Action, State, Message, Label> {
    val state: State
    val coroutineScope: CoroutineScope

    suspend fun dispatch(message: Message)
    suspend fun publish(label: Label)
    suspend fun dispatchAction(action: Action)
}

interface Executor<Intent, Action, State, Message, Label> {
    suspend fun executeIntent(
        intent: Intent,
        scope: ExecutorScope<Intent, Action, State, Message, Label>
    )

    suspend fun executeAction(
        action: Action,
        scope: ExecutorScope<Intent, Action, State, Message, Label>
    )
}
```

Store implementation (commonMain)

```kotlin
package mvi

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val executorFactory: () -> Executor<Intent, Action, State, Message, Label>,
    private val reducer: Reducer<State, Message>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val autoInit: Boolean = true
) : Store<Intent, State, Label> {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(dispatcher + job)

    private val _state = MutableStateFlow(initialState)
    override val state: StateFlow<State> = _state.asStateFlow()

    private val _labels = MutableSharedFlow<Label>()
    override val labels = _labels

    // Serialize top-level Intent/Action chains.
    private val intentActionMutex = Mutex()

    // Serialize reducer calls even from detached coroutines.
    private val reduceMutex = Mutex()

    // Start control
    private val startMutex = Mutex()
    private var started = false

    private val executor: Executor<Intent, Action, State, Message, Label> = executorFactory()

    // Context element marking we're already inside the critical section.
    private object InChain :
        AbstractCoroutineContextElement(Key) {
        object Key : CoroutineContext.Key<InChain>
    }

    private inner class ExecutorScopeImpl :
        ExecutorScope<Intent, Action, State, Message, Label> {

        override val state: State
            get() = _state.value

        override val coroutineScope: CoroutineScope
            get() = scope

        override suspend fun dispatch(message: Message) {
            // Confinement + serialization of reduce
            withContext(scope.coroutineContext) {
                reduceMutex.withLock {
                    val newState = reducer.reduce(_state.value, message)
                    _state.value = newState
                }
            }
        }

        override suspend fun publish(label: Label) {
            withContext(scope.coroutineContext) {
                _labels.emit(label)
            }
        }

        override suspend fun dispatchAction(action: Action) {
            // If we're already inside a chain, inline without re-locking.
            if (coroutineContext[InChain] != null) {
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
        // Explicit, idempotent start.
        scope.launch {
            startIfNeeded()
        }
    }

    private suspend fun startIfNeeded() {
        startMutex.withLock {
            if (started) return
            started = true
            bootstrapper?.let { bs ->
                // Run bootstrapper; it can suspend and dispatch actions.
                scope.launch {
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
    }

    // ----------------- Internal helpers -----------------

    private suspend fun processIntentChain(intent: Intent) {
        executor.executeIntent(intent, execScope)
    }

    private suspend fun processActionChain(action: Action) {
        executor.executeAction(action, execScope)
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
```

Example (commonMain)

```kotlin
// ----- Model -----

sealed interface CounterIntent {
    object IncrementClicked : CounterIntent
    object DecrementClicked : CounterIntent
    object RefreshClicked : CounterIntent
}

sealed interface CounterAction {
    object LoadFromRepository : CounterAction
}

sealed interface CounterMessage {
    object Loading : CounterMessage
    data class Loaded(val value: Int) : CounterMessage
    object Increment : CounterMessage
    object Decrement : CounterMessage
}

data class CounterState(
    val value: Int = 0,
    val isLoading: Boolean = false
)

sealed interface CounterLabel {
    object ShowError : CounterLabel
}

// ----- Reducer -----

object CounterReducer : Reducer<CounterState, CounterMessage> {
    override fun reduce(state: CounterState, msg: CounterMessage): CounterState =
        when (msg) {
            CounterMessage.Loading -> state.copy(isLoading = true)
            is CounterMessage.Loaded -> state.copy(value = msg.value, isLoading = false)
            CounterMessage.Increment -> state.copy(value = state.value + 1)
            CounterMessage.Decrement -> state.copy(value = state.value - 1)
        }
}

// ----- Executor -----

class CounterExecutor(
    private val loadFromRepo: suspend () -> Int
) : Executor<CounterIntent, CounterAction, CounterState, CounterMessage, CounterLabel> {

    override suspend fun executeIntent(
        intent: CounterIntent,
        scope: ExecutorScope<CounterIntent, CounterAction, CounterState, CounterMessage, CounterLabel>
    ) {
        when (intent) {
            CounterIntent.IncrementClicked -> scope.dispatch(CounterMessage.Increment)
            CounterIntent.DecrementClicked -> scope.dispatch(CounterMessage.Decrement)
            CounterIntent.RefreshClicked   -> scope.dispatchAction(CounterAction.LoadFromRepository)
        }
    }

    override suspend fun executeAction(
        action: CounterAction,
        scope: ExecutorScope<CounterIntent, CounterAction, CounterState, CounterMessage, CounterLabel>
    ) {
        when (action) {
            CounterAction.LoadFromRepository -> {
                scope.dispatch(CounterMessage.Loading)
                try {
                    val value = loadFromRepo()
                    scope.dispatch(CounterMessage.Loaded(value))
                } catch (t: Throwable) {
                    scope.publish(CounterLabel.ShowError)
                }
            }
        }
    }
}

// ----- Bootstrapper -----

class CounterBootstrapper : Bootstrapper<CounterAction> {
    override suspend fun bootstrap(dispatchAction: suspend (CounterAction) -> Unit) {
        // Fire initial load.
        dispatchAction(CounterAction.LoadFromRepository)
    }
}

// ----- Store factory -----

fun createCounterStore(
    loadFromRepo: suspend () -> Int,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    autoInit: Boolean = true
): Store<CounterIntent, CounterState, CounterLabel> =
    CoroutineStore(
        initialState = CounterState(),
        bootstrapper = CounterBootstrapper(),
        executorFactory = { CounterExecutor(loadFromRepo) },
        reducer = CounterReducer,
        dispatcher = dispatcher,
        autoInit = autoInit
    )
```

Usage notes

- KMP targets: Android, iOS, Desktop, JS/Web are supported as long as your project provides Dispatchers.Main.immediate (or inject another dispatcher).
- init() is idempotent and only starts the bootstrapper. Intents work regardless, but bootstrapper won’t run until init() if autoInit=false.
- Guarantees hold when you stick to structured concurrency within Executor/Bootstrapper. If you spawn detached jobs that call dispatchAction later, state is still thread-safe (reduceMutex) but you’re outside the “single chain at a time” rule.

Optional: platform default dispatcher
If you prefer a hard default that picks Main.immediate where available, add an expect/actual helper and use dispatcher = storeDefaultDispatcher():

```kotlin
// commonMain
expect fun storeDefaultDispatcher(): CoroutineDispatcher
```

Provide actuals per target (e.g., Android/JVM: Dispatchers.Main.immediate; iOS: Dispatchers.Main.immediate; Desktop: Dispatchers.Main.immediate; JS: Dispatchers.Main).

Open questions (to finalize semantics)

- Should sendIntent be allowed before init() when autoInit=false? In this draft, yes—only the bootstrapper is gated by init(). If you prefer to fully gate the store (reject/queue intents until init), I can flip the switch to enforce that.
