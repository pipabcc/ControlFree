package com.example.controlfree.ui.todo.components

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CardActionHintStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var store: CardActionHintStore

    @Before
    fun setUp() {
        preferences().edit().clear().commit()
        store = CardActionHintStore(context)
    }

    @After
    fun tearDown() {
        preferences().edit().clear().commit()
    }

    @Test
    fun emptyPageCreation_waitsForPersistedCard_andShowsOnlyOnce() {
        store.armForCreation(
            CardActionHintTab.TODO,
            isDataLoaded = true,
            wasEmpty = true
        )

        assertFalse(store.shouldShowWhenReady(CardActionHintTab.TODO, hasItems = false))
        assertTrue(store.shouldShowWhenReady(CardActionHintTab.TODO, hasItems = true))
        assertTrue(store.markShown(CardActionHintTab.TODO))
        assertFalse(store.shouldShowWhenReady(CardActionHintTab.TODO, hasItems = true))
    }

    @Test
    fun hintRemainsPendingUntilSnackbarWasActuallyShown() {
        store.armForCreation(
            CardActionHintTab.TODO,
            isDataLoaded = true,
            wasEmpty = true
        )

        assertTrue(store.shouldShowWhenReady(CardActionHintTab.TODO, hasItems = true))
        assertTrue(store.shouldShowWhenReady(CardActionHintTab.TODO, hasItems = true))
    }

    @Test
    fun initialLoadingStateCannotBeMistakenForEmptyPage() {
        store.armForCreation(
            CardActionHintTab.TODO,
            isDataLoaded = false,
            wasEmpty = true
        )

        assertFalse(store.shouldShowWhenReady(CardActionHintTab.TODO, hasItems = true))
    }

    @Test
    fun existingPageAndEdit_doNotArmHint() {
        store.armForCreation(
            CardActionHintTab.QUICK_NOTE,
            isDataLoaded = true,
            wasEmpty = false
        )
        store.armForCreation(
            CardActionHintTab.QUICK_NOTE,
            isDataLoaded = true,
            wasEmpty = true,
            isNewItem = false
        )

        assertFalse(store.shouldShowWhenReady(CardActionHintTab.QUICK_NOTE, hasItems = true))
    }

    @Test
    fun tabs_haveIndependentOneTimeState() {
        store.armForCreation(
            CardActionHintTab.LEDGER,
            isDataLoaded = true,
            wasEmpty = true
        )
        store.armForCreation(
            CardActionHintTab.ANNIVERSARY,
            isDataLoaded = true,
            wasEmpty = true
        )

        assertTrue(store.shouldShowWhenReady(CardActionHintTab.LEDGER, hasItems = true))
        assertTrue(store.markShown(CardActionHintTab.LEDGER))
        assertFalse(store.shouldShowWhenReady(CardActionHintTab.LEDGER, hasItems = true))
        assertTrue(store.shouldShowWhenReady(CardActionHintTab.ANNIVERSARY, hasItems = true))
    }

    @Test
    fun message_describesClickInteraction() {
        assertTrue(CardActionHintTab.QUICK_NOTE.message().startsWith("点击"))
    }

    private fun preferences() = context.getSharedPreferences(
        "todo_swipe_action_hints",
        Context.MODE_PRIVATE
    )
}
