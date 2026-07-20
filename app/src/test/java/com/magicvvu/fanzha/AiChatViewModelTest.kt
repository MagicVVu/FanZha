package com.magicvvu.fanzha

import androidx.test.core.app.ApplicationProvider
import com.magicvvu.fanzha.ui.viewmodels.AiChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AiChatViewModelTest {

    private lateinit var viewModel: AiChatViewModel
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        viewModel = AiChatViewModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state contains welcome message`() {
        val messages = viewModel.messages.value
        assertEquals(1, messages.size)
        assertFalse(messages[0].isFromUser)
        assertTrue(
            messages[0].text?.contains("AI反诈助手") == true,
        )
    }

    @Test
    fun `sendMessage adds user message and loading message`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        viewModel.sendMessage(context, "Hello")

        val messages = viewModel.messages.value
        assertEquals(3, messages.size)

        val userMsg = messages[1]
        assertTrue(userMsg.isFromUser)
        assertEquals("Hello", userMsg.text)

        val loadingMsg = messages[2]
        assertFalse(loadingMsg.isFromUser)
        assertTrue(loadingMsg.isLoading)
    }
}
