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
@file:JvmName("PromiseScheduling")

package com.shopify.promises

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Schedule execution of this [Promise] on provided executor
 *
 * @param executor to be scheduled on
 * @return [Promise]`<T, E>`
 */
fun <T, E> Promise<T, E>.startOn(executor: Executor): Promise<T, E> {
  return Promise<Unit, Nothing> {
    val canceled = AtomicBoolean()
    doOnCancel {
      canceled.set(true)
    }
    executor.execute {
      if (!canceled.get()) onSuccess(Unit)
    }
  }
    .promoteError<Unit, E>()
    .then { this }
}

/**
 * Schedule delivering results of this promise on provide executor
 *
 * @param executor to be scheduled on
 * @return [Promise]`<T, E>`
 */
fun <T, E> Promise<T, E>.completeOn(executor: Executor): Promise<T, E> {
  return bind { result ->
    Promise<T, E> {
      val canceled = AtomicBoolean()
      doOnCancel {
        canceled.set(true)
      }
      executor.execute {
        if (!canceled.get()) {
          when (result) {
            is Promise.Result.Success -> onSuccess(result.value)
            is Promise.Result.Error -> onError(result.error)
          }
        }
      }
    }
  }
}