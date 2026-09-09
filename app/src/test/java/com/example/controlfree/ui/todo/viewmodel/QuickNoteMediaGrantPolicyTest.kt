package com.example.controlfree.ui.todo.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickNoteMediaGrantPolicyTest {
    @Test
    fun 有已保存记录引用时不释放授权() {
        assertFalse(shouldReleaseQuickNoteMediaGrant(savedReferenceCount = 1, draftOwnerCount = 0))
    }

    @Test
    fun 有草稿持有者时不释放授权() {
        assertFalse(shouldReleaseQuickNoteMediaGrant(savedReferenceCount = 0, draftOwnerCount = 1))
    }

    @Test
    fun 无保存引用且无草稿持有者时释放授权() {
        assertTrue(shouldReleaseQuickNoteMediaGrant(savedReferenceCount = 0, draftOwnerCount = 0))
    }

    @Test
    fun 清理期间另一草稿保存同一图片时旧查询结果不会释放授权() {
        val registry = QuickNoteDraftMediaGrantRegistry()
        val uri = "content://quick-note/image"
        registry.acquire(uri)
        registry.relinquish(uri)
        val cleanupBeforeDatabaseQuery = registry.beginCleanup(uri)

        // 模拟旧清理查库得到 0 后，另一草稿完成获取、保存和所有权转移。
        registry.acquire(uri)
        registry.relinquish(uri)

        var wasReleased = false
        val didRelease = registry.releaseIfUnused(
            ticket = cleanupBeforeDatabaseQuery,
            savedReferenceCount = 0
        ) {
            wasReleased = true
        }

        assertFalse(didRelease)
        assertFalse(wasReleased)
    }

    @Test
    fun 清理期间状态未变化且没有引用时释放授权() {
        val registry = QuickNoteDraftMediaGrantRegistry()
        val uri = "content://quick-note/unused-image"
        val cleanupTicket = registry.beginCleanup(uri)

        var wasReleased = false
        val didRelease = registry.releaseIfUnused(
            ticket = cleanupTicket,
            savedReferenceCount = 0
        ) {
            wasReleased = true
        }

        assertTrue(didRelease)
        assertTrue(wasReleased)
    }

    @Test
    fun 删除图片闪记等待撤销期间不会被旧清理任务释放授权() {
        val registry = QuickNoteDraftMediaGrantRegistry()
        val uri = "content://quick-note/undo-image"

        registry.acquire(uri)
        val staleCleanup = registry.beginCleanup(uri)
        registry.relinquish(uri)

        var wasReleased = false
        val didRelease = registry.releaseIfUnused(staleCleanup, savedReferenceCount = 0) {
            wasReleased = true
        }

        assertFalse(didRelease)
        assertFalse(wasReleased)
    }
}
