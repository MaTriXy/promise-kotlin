/*
 *   The MIT License (MIT)
 *
 *   Copyright (c) 2017 Shopify Inc.
 *
 *   Permission is hereby granted, free of charge, to any person obtaining a copy
 *   of this software and associated documentation files (the "Software"), to deal
 *   in the Software without restriction, including without limitation the rights
 *   to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *   copies of the Software, and to permit persons to whom the Software is
 *   furnished to do so, subject to the following conditions:
 *
 *   The above copyright notice and this permission notice shall be included in
 *   all copies or substantial portions of the Software.
 *
 *   THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *   IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *   FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *   AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *   LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *   OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *   THE SOFTWARE.
 */
package com.shopify.promises

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Callback to be notified when [Promise] resolved
 *
 * Provided [Promise.Result]`<T, E>` argument represents the result of [Promise]`<T, E>` execution and it can be either
 * [Promise.Result.Success] or [Promise.Result.Error]
 *
 * @see Promise.Result
 */
typealias PromiseCallback<T, E> = (result: Promise.Result<T, E>) -> Unit

/**
 * Callback to be notified when [Promise] resolved with [Promise.Result.Success]
 *
 * Provided argument represents the success result of [Promise]`<T, E>` execution
 */
typealias PromiseOnResolveCallback<T> = (value: T) -> Unit

/**
 * Callback to be notified when [Promise] resolved with [Promise.Result.Error]
 *
 * Provided argument represents the error result of [Promise]`<T, E>` execution
 */
typealias PromiseOnRejectCallback<E> = (error: E) -> Unit

/**
 * Action to be performed when [Promise] has been canceled
 *
 * @see Promise.Subscriber.onCancel
 */
typealias PromiseCancelDelegate = () -> Unit

/**
 * A Promise task to be executed
 *
 * Task is responsible to notify about result of its execution. Task is represented by function with [Promise.Subscriber] receiver and
 * after completion it must call either [Promise.Subscriber.resolve] to signal success result or [Promise.Subscriber.reject] in case of
 * failure. Passed subscriber as an argument for this task provides [Promise.Subscriber.onCancel] function that task can use to register
 * any action (such as terminate long running job, clean up resources, etc.) to be performed when [Promise] has been canceled.
 *
 * @see Promise.Subscriber
 */
typealias PromiseTask<T, E> = Promise.Subscriber<T, E>.() -> Unit

/**
 * Function that transforms `(Promise.Result<T, E>) -> Promise<T1, E1>` used by binding operation
 *
 * @see Promise.bind
 */
typealias PromiseMonadTransformation<T, E, T1, E1> = (result: Promise.Result<T, E>) -> Promise<T1, E1>

/**
 * A Promise that can either be resolved with [Promise.Result.Success]`<T, E>` or [Promise.Result.Error]`<T, E>`
 *
 * The result this promise resolution will be delivered in provided callback [Promise.whenComplete].
 *
 * @see Promise.Result.Success
 * @see Promise.Result.Error
 * @see Promise.whenComplete
 */
class Promise<T, E> private constructor(state: State<T, E>) {
  private val state: AtomicReference<State<T, E>> = AtomicReference(state)

  /**
   * Create a new promise that executes provided task
   *
   * Task is represented by a function with a receiver [Promise.Subscriber] that must be used to notify about execution result, it must call
   * either [Promise.Subscriber.resolve] to signal success result or [Promise.Subscriber.reject] in case of failure. Additionally
   * subscriber provides [Promise.Subscriber.onCancel] function that task can use to register any action (such as terminate long running
   * job, clean up resources, etc.) to be performed when this [Promise] has been canceled.
   *
   * Example:
   *
   * ```
   * Promise<String, RuntimeException> {
   *   doOnCancel {
   *     // will be called if promise has been canceled
   *     // it is task responsibility to terminate any internal long running jobs
   *     // and clean up resources here
   *   }
   *   // do some job
   *   onSuccess("Done!")
   *   // onError(RuntimeException("Failed"))
   * }
   * ```
   */
  constructor(startDelegate: PromiseTask<T, E>) : this(
    State.Idle(
      startDelegate = startDelegate,
      callbacks = emptySet()
    )
  )

  /**
   * Start promise task execution
   *
   * Result will be delivered via [PromiseCallback].
   *
   * @param callback to be called when promise succeed or failed
   * @return [Promise]`<T, E>`
   * @see Promise.Result.Success
   * @see Promise.Result.Error
   */
  fun whenComplete(callback: PromiseCallback<T, E>): Promise<T, E> {
    appendCallback(callback)
    if (state.get() is State.Idle) {
      start()
    }
    return this
  }

  /**
   * Start promise task execution
   *
   * Result will be delivered via provided [PromiseOnResolveCallback] or [PromiseOnRejectCallback] callbacks.
   *
   * @param onResolve to be called when promise succeed
   * @param onResolve to be called when promise failed
   * @return [Promise]`<T, E>`
   * @see Promise.Result.Success
   * @see Promise.Result.Error
   */
  fun whenComplete(onResolve: PromiseOnResolveCallback<T>, onReject: PromiseOnRejectCallback<E>): Promise<T, E> {
    whenComplete {
      when (it) {
        is Promise.Result.Success -> onResolve(it.value)
        is Promise.Result.Error -> onReject(it.error)
      }
    }
    return this
  }

  /**
   * Signal promise to cancel task execution if it hasn't been completed yet
   *
   * @return [Promise]`<T, E>`
   * @see PromiseTask
   * @see Promise.Subscriber.onCancel
   */
  fun cancel() {
    do {
      val oldState = state.get().let {
        if (it is State.Cancelled) return else it
      }
      val newState = State.Cancelled<T, E>()
      if (state.compareAndSet(oldState, newState)) {
        (oldState as? State.InProgress)?.apply { cancelDelegate() }
        return
      }
    } while (true)
  }

  /**
   * Binding operation, bind the result of this [Promise] and return a new [Promise] with transformed result
   *
   * @param transform binding operation function that transforms `(Promise.Result<T, E>) -> Promise<T1, E1>`
   * @return [Promise]`T1, E1`
   */
  fun <T1, E1> bind(transform: PromiseMonadTransformation<T, E, T1, E1>): Promise<T1, E1> {
    val upstreamPromise = this
    return Promise {
      val cancelDelegate = AtomicReference<PromiseCancelDelegate> {
        upstreamPromise.cancel()
      }

      val canceled = AtomicBoolean()
      onCancel {
        canceled.set(true)
        cancelDelegate.get().invoke()
      }

      val subscriber = this
      upstreamPromise.whenComplete {
        val downStreamPromise = transform(it)
        cancelDelegate.set {
          downStreamPromise.cancel()
        }

        if (canceled.get()) {
          downStreamPromise.cancel()
          return@whenComplete
        }

        downStreamPromise.whenComplete {
          subscriber.dispatch(it)
        }
      }
    }
  }

  private fun appendCallback(callback: PromiseCallback<T, E>) {
    do {
      val oldState = state.get()
      val newState = when (oldState) {
        is State.Idle, is State.InProgress -> oldState.appendCallback(callback)
        is State.Complete -> callback(oldState.result).let { oldState }
        else -> oldState
      }
    } while (oldState !== newState && !state.compareAndSet(oldState, newState))
  }

  private fun start() {
    val subscriber = object : Promise.Subscriber<T, E> {
      val cancelDelegate = AtomicReference<PromiseCancelDelegate>()

      override fun resolve(value: T) {
        complete(Promise.Result.Success(value))
      }

      override fun reject(error: E) {
        complete(Promise.Result.Error(error))
      }

      override fun onCancel(cancelDelegate: PromiseCancelDelegate) {
        this.cancelDelegate.set(cancelDelegate)
      }
    }

    do {
      val oldState = state.get() as? State.Idle ?: return
      val startDelegate = oldState.startDelegate
      val newState = State.InProgress(oldState.callbacks) {
        subscriber.cancelDelegate.get()?.invoke()
      }
      if (state.compareAndSet(oldState, newState)) {
        startDelegate(subscriber)
        return
      }
    } while (true)
  }

  private fun complete(result: Promise.Result<T, E>) {
    val oldState = state.get()?.let {
      when (it) {
        is State.Idle -> throw IllegalStateException("Cannot mark an idle promise as completed.")
        is State.InProgress -> it
        is State.Complete -> throw IllegalStateException("Cannot complete a promise twice.")
        is State.Cancelled -> null
      }
    } ?: return

    val newState = State.Complete(result)
    if (state.compareAndSet(oldState, newState)) {
      oldState.callbacks.forEach {
        it(newState.result)
      }
    }
  }

  private sealed class State<T, E> {
    data class Idle<T, E>(
      val startDelegate: PromiseTask<T, E>,
      val callbacks: Set<PromiseCallback<T, E>>
    ) : State<T, E>()

    data class InProgress<T, E>(
      val callbacks: Set<PromiseCallback<T, E>>,
      val cancelDelegate: PromiseCancelDelegate
    ) : State<T, E>()

    data class Complete<T, E>(val result: Promise.Result<T, E>) : State<T, E>()

    class Cancelled<T, E> : State<T, E>()

    fun appendCallback(callback: PromiseCallback<T, E>): State<T, E> = when (this) {
      is Idle -> Idle(
        startDelegate = startDelegate,
        callbacks = callbacks + callback
      )
      is InProgress -> InProgress(
        cancelDelegate = cancelDelegate,
        callbacks = callbacks + callback
      )
      else -> this
    }
  }

  /**
   * Subscriber to be passed as an argument to promise task function [PromiseTask]
   *
   * Used by task to notify about its execution result.
   */
  interface Subscriber<T, E> {
    /**
     * Called when promise task execution finished with success result
     *
     * @param value success result
     */
    fun resolve(value: T)

    /**
     * Called when promise task execution finished with failure result
     *
     * @param error failure result
     */
    fun reject(error: E)

    /**
     * Register action to be preformed when promise has been canceled
     *
     * @param cancelDelegate action to be invoked
     */
    fun onCancel(cancelDelegate: PromiseCancelDelegate)
  }

  /**
   * Result of promise task execution
   */
  sealed class Result<T, E> {
    /**
     * Success result of promise task execution
     *
     * @property value success result
     */
    data class Success<T, E>(val value: T) : Promise.Result<T, E>()

    /**
     * Failure result of promise task execution
     *
     * @param error error result
     */
    data class Error<T, E>(val error: E) : Promise.Result<T, E>()
  }

  companion object {
    /**
     * Wrap value into successfully resolved promise
     *
     * @param value to be wrapped
     * @return [Promise]`<T, Nothing>`
     */
    fun <T> of(value: T): Promise<T, Nothing> = Promise(State.Complete(Promise.Result.Success(value)))

    /**
     * Wrap value into successfully resolved promise with specified type of error
     *
     * @param value to be wrapped
     * @return [Promise]`<T, E>`
     */
    fun <T, E> ofSuccess(value: T): Promise<T, E> = Promise(State.Complete(Promise.Result.Success(value)))

    /**
     * Wrap error into failure promise
     *
     * @param error to be wrapped
     * @return [Promise]`<T, E>`
     */
    fun <T, E> ofError(error: E): Promise<T, E> = Promise(State.Complete(Promise.Result.Error(error)))
  }
}

internal fun <T, E> Promise.Subscriber<T, E>.dispatch(result: Promise.Result<T, E>) {
  when (result) {
    is Promise.Result.Success -> this.resolve(result.value)
    is Promise.Result.Error -> this.reject(result.error)
  }
}