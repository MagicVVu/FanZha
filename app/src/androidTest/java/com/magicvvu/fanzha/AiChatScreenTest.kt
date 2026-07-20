package com.magicvvu.fanzha

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsDisplayed
import com.magicvvu.fanzha.ui.screens.AiChatScreen
import org.junit.Rule
import org.junit.Test

class AiChatScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun aiChatScreen_displaysInitialMessageAndInputArea() {
        composeTestRule.setContent {
            AiChatScreen()
        }

        // Check initial AI message
        composeTestRule.onNodeWithText("你好！我是您的AI反诈助手。你可以连续发送文字、截图、录音和文件，我会结合历史证据继续判断。").assertIsDisplayed()

        // Check input area
        composeTestRule.onNodeWithContentDescription("输入消息").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("发送消息").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("添加附件").assertIsDisplayed()
    }

    @Test
    fun aiChatScreen_sendMessage_displaysUserMessageAndLoading() {
        composeTestRule.setContent {
            AiChatScreen()
        }

        // Type text
        composeTestRule.onNodeWithContentDescription("输入消息").performTextInput("Hello AI")

        // Click send
        composeTestRule.onNodeWithContentDescription("发送消息").performClick()

        // Check user message is displayed
        composeTestRule.onNodeWithText("Hello AI").assertIsDisplayed()

        // Check loading state is displayed
        composeTestRule.onNodeWithText("思考中...").assertIsDisplayed()
    }
}
