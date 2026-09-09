package com.example.controlfree.todo.habit.runtime

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class HabitReminderReceiverConcurrencyTest {
    @Test
    fun `同进程重排任务按锁顺序串行执行`() = runTest {
        val events = mutableListOf<String>()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val sequence = AtomicInteger(0)

        val first = async {
            HabitReminderReceiver.withReconciliationLock {
                events += "first-start-${sequence.incrementAndGet()}"
                firstEntered.complete(Unit)
                releaseFirst.await()
                events += "first-end-${sequence.incrementAndGet()}"
            }
        }
        firstEntered.await()

        val second = async {
            HabitReminderReceiver.withReconciliationLock {
                events += "second-${sequence.incrementAndGet()}"
            }
        }
        runCurrent()
        assertEquals(listOf("first-start-1"), events)

        releaseFirst.complete(Unit)
        first.await()
        second.await()

        assertEquals(
            listOf("first-start-1", "first-end-2", "second-3"),
            events
        )
    }
}
