package com.magicvvu.fanzha

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import com.magicvvu.fanzha.ui.screens.SecurityScoreCard
import org.junit.Rule
import org.junit.Test

class SecurityScoreCardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testSecurityScoreCard_displaysContent() {
        composeTestRule.setContent {
            SecurityScoreCard(safetyIndex = 70, safetyIndexPrecise = 70.00)
        }

        // Wait for the animation to finish (it takes 1500ms)
        composeTestRule.mainClock.advanceTimeBy(1600)

        // Check if the score "70" is displayed
        composeTestRule.onNodeWithText("70").assertIsDisplayed()

        // Check if the label is displayed
        composeTestRule.onNodeWithText("安全指数").assertIsDisplayed()

        // Check if the status text is displayed
        composeTestRule.onNodeWithText("良好").assertIsDisplayed()

        // Check if the description text is displayed (60–79 良好档)
        composeTestRule.onNodeWithText("整体较安全，仍有可优化项，建议关注系统提醒并及时处理。").assertIsDisplayed()

        // Check if the protected days text is displayed
        composeTestRule.onNodeWithText("已保护天数：120天").assertIsDisplayed()

        // Check if the button is displayed
        composeTestRule.onNodeWithText("查看安全分析报告").assertIsDisplayed()
    }
}