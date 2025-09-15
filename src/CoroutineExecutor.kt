package coroutineexecutor

import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.Callable
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit
import java.lang.Runnable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.future.asCompletableFuture
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.DelicateCoroutinesApi

// An ExecutorService that spawns an unbounded amount of coroutines
// Note: it uses GlobalScope for the submit() functions because
// other coroutine scopes, like coroutineScope() or runBlocking()
// only return once all the child coroutines have completed, which
// blocks the calling thread (i.e., the HTTP server thread in this case)
class CoroutineExecutor : ExecutorService {
	private var closing = false
	private var closed = false
	private val deferreds = mutableListOf<Deferred<Any?>>()

	// Clean up references to completed deferreds
	private fun removeCompletedDeferreds() {
		var i = deferreds.size - 1
		while (i >= 0) {
			if (deferreds[i].isCompleted) {
				deferreds.removeLast()
			}
			i --
		}
	}

	private fun timeoutToMillis(timeout: Long, timeUnit: TimeUnit): Long {
		return when (timeUnit) {
			TimeUnit.DAYS -> timeout * 86_400_000L
			TimeUnit.HOURS -> timeout * 3_600_000L
			TimeUnit.MINUTES -> timeout * 60_000L
			TimeUnit.SECONDS -> timeout * 1_000L
			TimeUnit.MILLISECONDS -> timeout
			TimeUnit.MICROSECONDS -> timeout / 1_000L
			TimeUnit.NANOSECONDS -> timeout / 1_000_000L
		}
	}

	override fun awaitTermination(timeout: Long, timeUnit: TimeUnit): Boolean {
		val msTimeout: Long = timeoutToMillis(timeout, timeUnit)

		shutdownNow()
		val startTime = System.currentTimeMillis()

		while (!deferreds.isEmpty()) {
			removeCompletedDeferreds()
			if ((System.currentTimeMillis() - startTime) >= msTimeout) {
				return false
			}
		}

		return true
	}

	override fun shutdown() {
		closing = true
	}

	override fun shutdownNow(): List<Runnable> {
		closing = true
		for (job in deferreds) {
			if ((job.isActive || !job.isCompleted) && !job.isCancelled) {
				job.cancel()
			}
		}
		return listOf()
	}

	override fun close() = runBlocking {
		closing = true
		for (job in deferreds) {
			job.join()
		}
		closed = true
	}

	override fun isShutdown() = closing
	override fun isTerminated() = closed

	@OptIn(DelicateCoroutinesApi::class)
	override fun submit(task: Runnable): Future<Any?> {
		if (closing) throw RejectedExecutionException()
		removeCompletedDeferreds()
		val d = GlobalScope.async { task.run() }
		deferreds.add(d)
		return d.asCompletableFuture()
	}

	@OptIn(DelicateCoroutinesApi::class)
	override fun <T> submit(task: Runnable, result: T): Future<T> {
		if (closing) throw RejectedExecutionException()
		removeCompletedDeferreds()
		val d = GlobalScope.async {
			task.run();
			return@async result
		}
		deferreds.add(d)
		return d.asCompletableFuture()
	}

	@OptIn(DelicateCoroutinesApi::class)
	override fun <T> submit(task: Callable<T>): Future<T> {
		if (closing) throw RejectedExecutionException()
		removeCompletedDeferreds()
		val d = GlobalScope.async { task.call() }
		deferreds.add(d)
		return d.asCompletableFuture()
	}

	override fun execute(task: Runnable) { submit(task); }

	override fun <T> invokeAll(tasks: Collection<Callable<T>>): List<Future<T>> = runBlocking {
		if (closing) throw RejectedExecutionException()
		val invokedDeferreds = mutableListOf<Deferred<T>>()
		for (task in tasks) {
			invokedDeferreds.add(async { task.call() })
		}
		for (def in invokedDeferreds) {
			def.join()
		}

		return@runBlocking invokedDeferreds.map { it.asCompletableFuture() }
	}

	override fun <T> invokeAll(tasks: Collection<Callable<T>>, timeout: Long, timeUnit: TimeUnit): List<Future<T>> = runBlocking {
		if (closing) throw RejectedExecutionException()

		val msTimeout = timeoutToMillis(timeout, timeUnit)
		val startTime = System.currentTimeMillis()
		var timeoutExpired = false

		val invokedDeferreds = mutableListOf<Deferred<T>>()
		for (task in tasks) {
			invokedDeferreds.add(async { task.call() })
		}
		while (invokedDeferreds.any { !it.isCompleted } && !timeoutExpired) {
			timeoutExpired = System.currentTimeMillis() - startTime >= msTimeout
		}
		for (def in invokedDeferreds.filter { it.isActive }) {
			def.cancel()
		}

		return@runBlocking invokedDeferreds.map { it.asCompletableFuture() }
	}

	override fun <T> invokeAny(tasks: Collection<Callable<T>>): T = runBlocking {
		val invokedDeferreds = mutableListOf<Deferred<T>>()

		for (task in tasks) {
			invokedDeferreds.add(async { task.call() })
		}
		var completedIdx = -1
		while (completedIdx == -1) {
			completedIdx = invokedDeferreds.indexOfFirst { it.isCompleted }
		}
		for (def in invokedDeferreds.filter { it.isActive }) {
			def.cancel()
		}
		return@runBlocking invokedDeferreds[completedIdx].await()
	}

	override fun <T> invokeAny(tasks: Collection<Callable<T>>, timeout: Long, timeUnit: TimeUnit): T = runBlocking {
		val msTimeout = timeoutToMillis(timeout, timeUnit)
		val startTime = System.currentTimeMillis()
		val invokedDeferreds = mutableListOf<Deferred<T>>()

		for (task in tasks) {
			invokedDeferreds.add(async { task.call() })
		}
		var completedIdx = -1
		var timeoutExpired = false
		while (completedIdx == -1 && !timeoutExpired) {
			completedIdx = invokedDeferreds.indexOfFirst { it.isCompleted }
			timeoutExpired = System.currentTimeMillis() - startTime >= msTimeout
		}
		for (def in invokedDeferreds.filter { it.isActive }) {
			def.cancel()
		}
		if (timeoutExpired) throw TimeoutException()
		return@runBlocking invokedDeferreds[completedIdx].await()
	}
}
