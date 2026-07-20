package com.magicvvu.fanzha.ui.viewmodels

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfileViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var application: Application

    @Before
    fun setup() {
        application = ApplicationProvider.getApplicationContext()
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testInitialState() {
        val viewModel = ProfileViewModel(application)
        assertNotNull(viewModel.currentUser)
        assertTrue(viewModel.availableUsers.isNotEmpty())
        assertFalse(viewModel.isSwitching)
        assertNull(viewModel.switchError)
    }

    @Test
    fun testSwitchUser_Success() = runTest(testDispatcher) {
        val viewModel = ProfileViewModel(application)
        val targetUser = UserProfile(id = "2", name = "另一账户")
        
        viewModel.switchUser(targetUser)

        assertEquals(targetUser, viewModel.currentUser)
        assertFalse(viewModel.isSwitching)
        assertNull(viewModel.switchError)
    }

    @Test
    fun testSwitchUser_SameUser() = runTest(testDispatcher) {
        val viewModel = ProfileViewModel(application)
        val currentUser = viewModel.currentUser
        
        viewModel.switchUser(currentUser)
        
        testScheduler.advanceUntilIdle()
        
        assertFalse(viewModel.isSwitching)
        assertEquals(currentUser, viewModel.currentUser)
    }
}
