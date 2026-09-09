package com.example.controlfree

import com.example.controlfree.security.VerificationResult
import com.example.controlfree.security.VerificationStatus
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

class LockAuthenticationExecutorTest {
    @Test
    fun `校验在后台线程运行且结果只由指定执行器交付`() {
        val callbacks = LinkedBlockingQueue<Runnable>()
        val worker = Executors.newSingleThreadExecutor()
        val executor = LockAuthenticationExecutor(
            resultExecutor = Executor(callbacks::add),
            workerExecutor = worker
        )
        val callerThread = Thread.currentThread()
        var workerThread = callerThread
        var deliveredResult: VerificationResult? = null

        try {
            assertTrue(executor.submit(
                task = {
                    workerThread = Thread.currentThread()
                    VerificationResult(VerificationStatus.SUCCESS)
                },
                onResult = { deliveredResult = it.getOrNull() }
            ))

            val callback = callbacks.poll(2, TimeUnit.SECONDS)
            assertNotSame(callerThread, workerThread)
            assertEquals(null, deliveredResult)
            requireNotNull(callback).run()
            assertEquals(VerificationStatus.SUCCESS, deliveredResult?.status)
        } finally {
            executor.close()
        }
    }

    @Test
    fun `同一代次只允许一个校验任务`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = LockAuthenticationExecutor(
            resultExecutor = Executor(Runnable::run),
            workerExecutor = Executors.newSingleThreadExecutor()
        )

        try {
            assertTrue(executor.submit(
                task = {
                    started.countDown()
                    release.await(2, TimeUnit.SECONDS)
                    VerificationResult(VerificationStatus.FAILURE)
                },
                onResult = {}
            ))
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertFalse(executor.submit(
                task = { VerificationResult(VerificationStatus.SUCCESS) },
                onResult = {}
            ))
        } finally {
            release.countDown()
            executor.close()
        }
    }

    @Test
    fun `界面代次失效后丢弃迟到结果并允许新任务`() {
        val callbacks = LinkedBlockingQueue<Runnable>()
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondCompleted = CountDownLatch(1)
        val executor = LockAuthenticationExecutor(
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
                    VerificationResult(VerificationStatus.SUCCESS)
                },
                onResult = { firstDelivered = true }
            ))
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS))

            executor.invalidate()
            assertTrue(executor.submit(
                task = { VerificationResult(VerificationStatus.FAILURE) },
                onResult = {
                    secondDelivered = true
                    secondCompleted.countDown()
                }
            ))
            releaseFirst.countDown()

            repeat(2) {
                callbacks.poll(2, TimeUnit.SECONDS)?.run()
            }
            assertTrue(secondCompleted.await(2, TimeUnit.SECONDS))
            assertFalse(firstDelivered)
            assertTrue(secondDelivered)
        } finally {
            releaseFirst.countDown()
            executor.close()
        }
    }
}
