package com.example.controlfree.ui.main

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialTaskExecutorTest {
    @Test
    fun `凭据任务在后台线程运行且结果由指定执行器交付`() {
        val callbacks = LinkedBlockingQueue<Runnable>()
        val worker = Executors.newSingleThreadExecutor()
        val executor = CredentialTaskExecutor(
            resultExecutor = Executor(callbacks::add),
            workerExecutor = worker
        )
        val callerThread = Thread.currentThread()
        var taskThread = callerThread
        var deliveredValue: String? = null

        try {
            assertTrue(executor.submit(
                task = {
                    taskThread = Thread.currentThread()
                    "完成"
                },
                onResult = { deliveredValue = it.getOrNull() }
            ))

            val callback = callbacks.poll(2, TimeUnit.SECONDS)
            assertNotSame(callerThread, taskThread)
            assertEquals(null, deliveredValue)
            requireNotNull(callback).run()
            assertEquals("完成", deliveredValue)
        } finally {
            executor.close()
        }
    }

    @Test
    fun `同一界面代次只接受一个当前任务`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = CredentialTaskExecutor(
            resultExecutor = Executor(Runnable::run),
            workerExecutor = Executors.newSingleThreadExecutor()
        )

        try {
            assertTrue(executor.submit(
                task = {
                    started.countDown()
                    release.await(2, TimeUnit.SECONDS)
                },
                onResult = {}
            ))
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertFalse(executor.submit(task = {}, onResult = {}))
        } finally {
            release.countDown()
            executor.close()
        }
    }

    @Test
    fun `界面失效后丢弃迟到结果并允许新任务`() {
        val callbacks = LinkedBlockingQueue<Runnable>()
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val executor = CredentialTaskExecutor(
            resultExecutor = Executor(callbacks::add),
            workerExecutor = Executors.newSingleThreadExecutor()
        )
        var firstDelivered = false
        var secondDelivered = false

        try {
            assertTrue(executor.submit(
                task = {
                    firstStarted.countDown()
                    releaseFirst.await(2, TimeUnit.SECONDS)
                    "旧结果"
                },
                onResult = { firstDelivered = true }
            ))
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS))

            executor.invalidate()
            assertTrue(executor.submit(
                task = { "新结果" },
                onResult = { secondDelivered = it.getOrNull() == "新结果" }
            ))
            releaseFirst.countDown()

            repeat(2) {
                callbacks.poll(2, TimeUnit.SECONDS)?.run()
            }
            assertFalse(firstDelivered)
            assertTrue(secondDelivered)
        } finally {
            releaseFirst.countDown()
            executor.close()
        }
    }
}
