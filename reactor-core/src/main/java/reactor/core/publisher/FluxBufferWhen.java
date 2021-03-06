/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.core.publisher;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Function;
import java.util.function.Supplier;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.Exceptions;
import reactor.util.annotation.Nullable;

/**
 * buffers elements into possibly overlapping buffers whose boundaries are determined
 * by a start Publisher's element and a signal of a derived Publisher
 *
 * @param <T> the source value type
 * @param <OPEN> the value type of the publisher opening the buffers
 * @param <CLOSE> the value type of the publisher closing the individual buffers
 * @param <BUFFER> the collection type that holds the buffered values
 *
 * @see <a href="https://github.com/reactor/reactive-streams-commons">Reactive-Streams-Commons</a>
 */
final class FluxBufferWhen<T, OPEN, CLOSE, BUFFER extends Collection<? super T>>
		extends FluxOperator<T, BUFFER> {

	final Publisher<OPEN> start;

	final Function<? super OPEN, ? extends Publisher<CLOSE>> end;

	final Supplier<BUFFER> bufferSupplier;

	final Supplier<? extends Queue<BUFFER>> queueSupplier;

	FluxBufferWhen(Flux<? extends T> source,
			Publisher<OPEN> start,
			Function<? super OPEN, ? extends Publisher<CLOSE>> end,
			Supplier<BUFFER> bufferSupplier,
			Supplier<? extends Queue<BUFFER>> queueSupplier) {
		super(source);
		this.start = Objects.requireNonNull(start, "start");
		this.end = Objects.requireNonNull(end, "end");
		this.bufferSupplier = Objects.requireNonNull(bufferSupplier, "bufferSupplier");
		this.queueSupplier = Objects.requireNonNull(queueSupplier, "queueSupplier");
	}

	@Override
	public int getPrefetch() {
		return Integer.MAX_VALUE;
	}

	@Override
	public void subscribe(CoreSubscriber<? super BUFFER> actual) {
		BufferWhenMainSubscriber<T, OPEN, CLOSE, BUFFER> main =
				new BufferWhenMainSubscriber<>(actual, bufferSupplier, queueSupplier, start, end);

		actual.onSubscribe(main);

		BufferWhenOpenSubscriber<OPEN> bos = new BufferWhenOpenSubscriber<>(main);
		if (main.subscribers.add(bos)) {
			start.subscribe(bos);
			source.subscribe(main);
		}
	}

	static final class BufferWhenMainSubscriber<T, OPEN, CLOSE, BUFFER extends Collection<? super T>>
			implements InnerOperator<T, BUFFER> {

		final CoreSubscriber<? super BUFFER>                               actual;
		final Publisher<? extends OPEN>                                    bufferOpen;
		final Function<? super OPEN, ? extends Publisher<? extends CLOSE>> bufferClose;
		final Supplier<BUFFER>                                             bufferSupplier;
		final Disposable.Composite                                         subscribers;
		final Queue<BUFFER>                                                queue;

		volatile long requested;
		static final AtomicLongFieldUpdater<BufferWhenMainSubscriber> REQUESTED =
		AtomicLongFieldUpdater.newUpdater(BufferWhenMainSubscriber.class, "requested");

		volatile Subscription s;
		static final AtomicReferenceFieldUpdater<BufferWhenMainSubscriber, Subscription> S =
				AtomicReferenceFieldUpdater.newUpdater(BufferWhenMainSubscriber.class, Subscription.class, "s");

		volatile Throwable errors;
		static final AtomicReferenceFieldUpdater<BufferWhenMainSubscriber, Throwable>
				ERRORS =
				AtomicReferenceFieldUpdater.newUpdater(BufferWhenMainSubscriber.class, Throwable.class, "errors");

		volatile int windows;
		static final AtomicIntegerFieldUpdater<BufferWhenMainSubscriber> WINDOWS =
				AtomicIntegerFieldUpdater.newUpdater(BufferWhenMainSubscriber.class, "windows");

		volatile boolean done;
		volatile boolean cancelled;

		long                        index;
		LinkedHashMap<Long, BUFFER> buffers; //linkedHashMap important to keep the buffer order on final drain
		long                        emitted;

		BufferWhenMainSubscriber(CoreSubscriber<? super BUFFER> actual,
				Supplier<BUFFER> bufferSupplier, Supplier<? extends Queue<BUFFER>> queueSupplier,
				Publisher<? extends OPEN> bufferOpen,
				Function<? super OPEN, ? extends Publisher<? extends CLOSE>> bufferClose) {
			this.actual = actual;
			this.bufferOpen = bufferOpen;
			this.bufferClose = bufferClose;
			this.bufferSupplier = bufferSupplier;
			this.queue = queueSupplier.get();
			this.buffers = new LinkedHashMap<>();
			this.subscribers = Disposables.composite();
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.setOnce(S, this, s)) {
				s.request(Long.MAX_VALUE);
			}
		}

		@Override
		public CoreSubscriber<? super BUFFER> actual() {
			return actual;
		}

		@Override
		public void onNext(T t) {
			synchronized (this) {
				Map<Long, BUFFER> bufs = buffers;
				if (bufs == null) {
					return;
				}
				for (BUFFER b : bufs.values()) {
					b.add(t);
				}
			}
		}

		@Override
		public void onError(Throwable t) {
			if (Exceptions.addThrowable(ERRORS, this, t)) {
				subscribers.dispose();
				synchronized (this) {
					buffers = null;
				}
				done = true;
				drain();
			}
			else {
				Operators.onErrorDropped(t, actual.currentContext());
			}
		}

		@Override
		public void onComplete() {
			subscribers.dispose();
			synchronized (this) {
				Map<Long, BUFFER> bufs = buffers;
				if (bufs == null) {
					return;
				}
				for (BUFFER b : bufs.values()) {
					queue.offer(b);
				}
				buffers = null;
			}
			done = true;
			drain();
		}

		@Override
		public void request(long n) {
			Operators.addCap(REQUESTED, this, n);
			drain();
		}

		@Override
		public void cancel() {
			if (Operators.terminate(S, this)) {
				cancelled = true;
				subscribers.dispose();
				synchronized (this) {
					buffers = null;
				}
				if (WINDOWS.getAndIncrement(this) == 0) {
					queue.clear();
				}
			}
		}

		void drain() {
			if (WINDOWS.getAndIncrement(this) != 0) {
				return;
			}

			int missed = 1;
			long e = emitted;
			Subscriber<? super BUFFER> a = actual;
			Queue<BUFFER> q = queue;

			for (;;) {
				long r = requested;

				while (e != r) {
					if (cancelled) {
						q.clear();
						return;
					}

					boolean d = done;
					if (d && errors != null) {
						q.clear();
						Throwable ex = Exceptions.terminate(ERRORS, this);
						a.onError(ex);
						return;
					}

					BUFFER v = q.poll();
					boolean empty = v == null;

					if (d && empty) {
						a.onComplete();
						return;
					}

					if (empty) {
						break;
					}

					a.onNext(v);
					e++;
				}

				if (e == r) {
					if (cancelled) {
						q.clear();
						return;
					}

					if (done) {
						if (errors != null) {
							q.clear();
							Throwable ex = Exceptions.terminate(ERRORS, this);
							a.onError(ex);
							return;
						}
						else if (q.isEmpty()) {
							a.onComplete();
							return;
						}
					}
				}

				emitted = e;
				missed = WINDOWS.addAndGet(this, -missed);
				if (missed == 0) {
					break;
				}
			}
		}

		void open(OPEN token) {
			Publisher<? extends CLOSE> p;
			BUFFER buf;
			try {
				buf = Objects.requireNonNull(bufferSupplier.get(), "The bufferSupplier returned a null Collection");
				p = Objects.requireNonNull(bufferClose.apply(token), "The bufferClose returned a null Publisher");
			}
			catch (Throwable ex) {
				Exceptions.throwIfFatal(ex);
				Operators.terminate(S, this);
				if (Exceptions.addThrowable(ERRORS, this, ex)) {
					subscribers.dispose();
					synchronized (this) {
						buffers = null;
					}
					done = true;
					drain();
				}
				else {
					Operators.onErrorDropped(ex, actual.currentContext());
				}
				return;
			}

			long idx = index;
			index = idx + 1;
			synchronized (this) {
				Map<Long, BUFFER> bufs = buffers;
				if (bufs == null) {
					return;
				}
				bufs.put(idx, buf);
			}

			BufferWhenCloseSubscriber<T, BUFFER> bc = new BufferWhenCloseSubscriber<>(this, idx);
			subscribers.add(bc);
			p.subscribe(bc);
		}

		void openComplete(BufferWhenOpenSubscriber<OPEN> os) {
			subscribers.remove(os);
			if (subscribers.size() == 0) {
				Operators.terminate(S, this);
				done = true;
				drain();
			}
		}

		void close(BufferWhenCloseSubscriber<T, BUFFER> closer, long idx) {
			subscribers.remove(closer);
			boolean makeDone = false;
			if (subscribers.size() == 0) {
				makeDone = true;
				Operators.terminate(S, this);
			}
			synchronized (this) {
				Map<Long, BUFFER> bufs = buffers;
				if (bufs == null) {
					return;
				}
				queue.offer(buffers.remove(idx));
			}
			if (makeDone) {
				done = true;
			}
			drain();
		}

		void boundaryError(Disposable boundary, Throwable ex) {
			Operators.terminate(S, this);
			subscribers.remove(boundary);
			if (Exceptions.addThrowable(ERRORS, this, ex)) {
				subscribers.dispose();
				synchronized (this) {
					buffers = null;
				}
				done = true;
				drain();
			}
			else {
				Operators.onErrorDropped(ex, actual.currentContext());
			}
		}

		@Override
		@Nullable
		public Object scanUnsafe(Attr key) {
			if (key == Attr.PARENT) return s;
			if (key == Attr.ACTUAL) return actual;
			if (key == Attr.PREFETCH) return Integer.MAX_VALUE;
			if (key == Attr.BUFFERED) return buffers.values()
			                                        .stream()
			                                        .mapToInt(Collection::size)
			                                        .sum();
			if (key == Attr.CANCELLED) return cancelled;
			if (key == Attr.TERMINATED) return done;
			if (key == Attr.REQUESTED_FROM_DOWNSTREAM) return requested;
			if (key == Attr.ERROR) return errors;

			return null;
		}
	}

	static final class BufferWhenOpenSubscriber<OPEN>
			implements Disposable, InnerConsumer<OPEN> {

		volatile Subscription subscription;
		static final AtomicReferenceFieldUpdater<BufferWhenOpenSubscriber, Subscription> SUBSCRIPTION =
				AtomicReferenceFieldUpdater.newUpdater(BufferWhenOpenSubscriber.class, Subscription.class, "subscription");

		final BufferWhenMainSubscriber<?, OPEN, ?, ?> parent;

		BufferWhenOpenSubscriber(BufferWhenMainSubscriber<?, OPEN, ?, ?> parent) {
			this.parent = parent;
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.setOnce(SUBSCRIPTION, this, s)) {
				subscription.request(Long.MAX_VALUE);
			}
		}

		@Override
		public void dispose() {
			Operators.terminate(SUBSCRIPTION, this);
		}

		@Override
		public boolean isDisposed() {
			return subscription == Operators.cancelledSubscription();
		}

		@Override
		public void onNext(OPEN t) {
			parent.open(t);
		}

		@Override
		public void onError(Throwable t) {
			SUBSCRIPTION.lazySet(this, Operators.cancelledSubscription());
			parent.boundaryError(this, t);
		}

		@Override
		public void onComplete() {
			SUBSCRIPTION.lazySet(this, Operators.cancelledSubscription());
			parent.openComplete(this);
		}

		@Override
		@Nullable
		public Object scanUnsafe(Attr key) {
			if (key == Attr.ACTUAL) return parent;
			if (key == Attr.PARENT) return subscription;
			if (key == Attr.REQUESTED_FROM_DOWNSTREAM) return Long.MAX_VALUE;
			if (key == Attr.CANCELLED) return isDisposed();

			return null;
		}
	}

	static final class BufferWhenCloseSubscriber<T, BUFFER extends Collection<? super T>>
			implements Disposable, InnerConsumer<Object> {

		volatile Subscription subscription;
		static final AtomicReferenceFieldUpdater<BufferWhenCloseSubscriber, Subscription> SUBSCRIPTION =
				AtomicReferenceFieldUpdater.newUpdater(BufferWhenCloseSubscriber.class, Subscription.class, "subscription");

		final BufferWhenMainSubscriber<T, ?, ?, BUFFER> parent;
		final long                                      index;


		BufferWhenCloseSubscriber(BufferWhenMainSubscriber<T, ?, ?, BUFFER> parent, long index) {
			this.parent = parent;
			this.index = index;
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.setOnce(SUBSCRIPTION, this, s)) {
				subscription.request(Long.MAX_VALUE);
			}
		}

		@Override
		public void dispose() {
			Operators.terminate(SUBSCRIPTION, this);
		}

		@Override
		public boolean isDisposed() {
			return subscription == Operators.cancelledSubscription();
		}

		@Override
		public void onNext(Object t) {
			Subscription s = subscription;
			if (s != Operators.cancelledSubscription()) {
				SUBSCRIPTION.lazySet(this, Operators.cancelledSubscription());
				s.cancel();
				parent.close(this, index);
			}
		}

		@Override
		public void onError(Throwable t) {
			if (subscription != Operators.cancelledSubscription()) {
				SUBSCRIPTION.lazySet(this, Operators.cancelledSubscription());
				parent.boundaryError(this, t);
			}
			else {
				Operators.onErrorDropped(t, parent.actual.currentContext());
			}
		}

		@Override
		public void onComplete() {
			if (subscription != Operators.cancelledSubscription()) {
				SUBSCRIPTION.lazySet(this, Operators.cancelledSubscription());
				parent.close(this, index);
			}
		}

		@Override
		@Nullable
		public Object scanUnsafe(Attr key) {
			if (key == Attr.ACTUAL) return parent;
			if (key == Attr.PARENT) return subscription;
			if (key == Attr.REQUESTED_FROM_DOWNSTREAM) return Long.MAX_VALUE;
			if (key == Attr.CANCELLED) return isDisposed();

			return null;
		}
	}
}
