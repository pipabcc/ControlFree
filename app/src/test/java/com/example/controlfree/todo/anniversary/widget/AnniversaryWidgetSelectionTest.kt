package com.example.controlfree.todo.anniversary.widget

import com.example.controlfree.todo.AnniversaryItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnniversaryWidgetSelectionTest {
    @Test
    fun 已绑定纪念日关闭展示后组件保持空状态() {
        val selected = item("selected", showOnWidget = false, isPinnedTop = true)
        val fallback = item("fallback", showOnWidget = true, isPinnedTop = true)

        assertNull(selectAnniversaryWidgetItem(listOf(selected, fallback), "selected"))
    }

    @Test
    fun 未绑定时优先选择置顶且允许展示的纪念日() {
        val ordinary = item("ordinary", showOnWidget = true, isPinnedTop = false)
        val pinned = item("pinned", showOnWidget = true, isPinnedTop = true)

        assertEquals(
            "pinned",
            selectAnniversaryWidgetItem(listOf(ordinary, pinned), null)?.id
        )
    }

    private fun item(
        id: String,
        showOnWidget: Boolean,
        isPinnedTop: Boolean
    ): AnniversaryItemEntity = AnniversaryItemEntity(
        id = id,
        title = id,
        targetDateEpochMillis = 1_000L,
        isLunar = false,
        type = "COUNTDOWN",
        repeatRule = "NONE",
        isPinnedTop = isPinnedTop,
        showOnWidget = showOnWidget,
        createdAtEpochMillis = 1L
    )
}
