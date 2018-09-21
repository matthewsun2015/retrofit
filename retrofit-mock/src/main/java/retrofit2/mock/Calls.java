/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package retrofit2.mock;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;
import okhttp3.Request;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Invocation;
import retrofit2.Response;

/** Factory methods for creating {@link Call} instances which immediately respond or fail. */
public final class Calls {
  private static final Method DEFER_METHOD = method("defer", Callable.class);
  private static final Method RESPONSE_T_METHOD = method("response", Object.class);
  private static final Method RESPONSE_RESPONSE_METHOD = method("response", Response.class);
  private static final Method FAILURE_IOEXCEPTION_METHOD = method("failure", IOException.class);
  private static final Method FAILURE_THROWABLE_METHOD = method("failure", Throwable.class);

  private static Method method(String name, Class<?>... parameterTypes) {
    try {
      return Calls.class.getDeclaredMethod(name, parameterTypes);
    } catch (NoSuchMethodException e) {
      throw new AssertionError();
    }
  }

  /**
   * Invokes {@code callable} once for the returned {@link Call} and once for each instance that is
   * obtained from {@linkplain Call#clone() cloning} the returned {@link Call}.
   */
  public static <T> Call<T> defer(Callable<Call<T>> callable) {
    Invocation invocation = new Invocation(DEFER_METHOD, Arrays.asList(callable));
    return new DeferredCall<>(invocation, callable);
  }

  public static <T> Call<T> response(@Nullable T successValue) {
    Invocation invocation = new Invocation(RESPONSE_T_METHOD, Arrays.asList(successValue));
    return new FakeCall<>(invocation, Response.success(successValue), null);
  }

  public static <T> Call<T> response(Response<T> response) {
    Invocation invocation = new Invocation(RESPONSE_RESPONSE_METHOD, Arrays.asList(response));
    return new FakeCall<>(invocation, response, null);
  }

  /** Creates a failed {@link Call} from {@code failure}. */
  public static <T> Call<T> failure(IOException failure) {
    // TODO delete this overload in Retrofit 3.0.
    Invocation invocation = new Invocation(FAILURE_IOEXCEPTION_METHOD, Arrays.asList(failure));
    return new FakeCall<>(invocation, null, failure);
  }

  /**
   * Creates a failed {@link Call} from {@code failure}.
   * <p>
   * Note: When invoking {@link Call#execute() execute()} on the returned {@link Call}, if
   * {@code failure} is a {@link RuntimeException}, {@link Error}, or {@link IOException} subtype
   * it is thrown directly. Otherwise it is "sneaky thrown" despite not being declared.
   */
  public static <T> Call<T> failure(Throwable failure) {
    Invocation invocation = new Invocation(FAILURE_THROWABLE_METHOD, Arrays.asList(failure));
    return new FakeCall<>(invocation, null, failure);
  }

  private Calls() {
    throw new AssertionError("No instances.");
  }

  static final class FakeCall<T> implements Call<T> {
    private final Invocation invocation;
    private final Response<T> response;
    private final Throwable error;
    private final AtomicBoolean canceled = new AtomicBoolean();
    private final AtomicBoolean executed = new AtomicBoolean();

    FakeCall(Invocation invocation, @Nullable Response<T> response, @Nullable Throwable error) {
      if ((response == null) == (error == null)) {
        throw new AssertionError("Only one of response or error can be set.");
      }
      this.invocation = invocation;
      this.response = response;
      this.error = error;
    }

    @Override public Response<T> execute() throws IOException {
      if (!executed.compareAndSet(false, true)) {
        throw new IllegalStateException("Already executed");
      }
      if (canceled.get()) {
        throw new IOException("canceled");
      }
      if (response != null) {
        return response;
      }
      throw FakeCall.<Error>sneakyThrow2(error);
    }

    @SuppressWarnings("unchecked") // Intentionally abusing this feature.
    private static <T extends Throwable> T sneakyThrow2(Throwable t) throws T {
      throw (T) t;
    }

    @SuppressWarnings("ConstantConditions") // Guarding public API nullability.
    @Override public void enqueue(Callback<T> callback) {
      if (callback == null) {
        throw new NullPointerException("callback == null");
      }
      if (!executed.compareAndSet(false, true)) {
        throw new IllegalStateException("Already executed");
      }
      if (canceled.get()) {
        callback.onFailure(this, new IOException("canceled"));
      } else if (response != null) {
        callback.onResponse(this, response);
      } else {
        callback.onFailure(this, error);
      }
    }

    @Override public boolean isExecuted() {
      return executed.get();
    }

    @Override public void cancel() {
      canceled.set(true);
    }

    @Override public boolean isCanceled() {
      return canceled.get();
    }

    @Override public Call<T> clone() {
      return new FakeCall<>(invocation, response, error);
    }

    @Override public Request request() {
      if (response != null) {
        return response.raw().request();
      }
      return new Request.Builder().url("http://localhost").build();
    }

    @Override public Invocation invocation() {
      return invocation;
    }
  }

  static final class DeferredCall<T> implements Call<T> {
    private final Callable<Call<T>> callable;
    private Call<T> delegate;
    private final Invocation invocation;

    DeferredCall(Invocation invocation, Callable<Call<T>> callable) {
      this.invocation = invocation;
      this.callable = callable;
    }

    private synchronized Call<T> getDelegate() {
      Call<T> delegate = this.delegate;
      if (delegate == null) {
        try {
          delegate = callable.call();
        } catch (Exception e) {
          delegate = failure(e);
        }
        this.delegate = delegate;
      }
      return delegate;
    }

    @Override public Response<T> execute() throws IOException {
      return getDelegate().execute();
    }

    @Override public void enqueue(Callback<T> callback) {
      getDelegate().enqueue(callback);
    }

    @Override public boolean isExecuted() {
      return getDelegate().isExecuted();
    }

    @Override public void cancel() {
      getDelegate().cancel();
    }

    @Override public boolean isCanceled() {
      return getDelegate().isCanceled();
    }

    @Override public Call<T> clone() {
      return new DeferredCall<>(invocation, callable);
    }

    @Override public Request request() {
      return getDelegate().request();
    }

    @Override public Invocation invocation() {
      return invocation;
    }
  }
}
