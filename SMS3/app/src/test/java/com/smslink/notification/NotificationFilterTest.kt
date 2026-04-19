package com.smslink.notification

import android.content.SharedPreferences
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * NotificationFilter 单元测试
 */
class NotificationFilterTest {

    private lateinit var preferences: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var filter: NotificationFilter

    @Before
    fun setup() {
        editor = mockk(relaxed = true)
        preferences = mockk(relaxed = true)
        every { preferences.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.putStringSet(any(), any()) } returns editor
        every { editor.apply() } just Runs

        filter = NotificationFilter(preferences)
    }

    @Test
    fun `shouldSync returns true for all mode`() {
        // Given
        every { preferences.getString(NotificationFilter.PREF_FILTER_MODE, any()) } returns
            NotificationFilter.FILTER_MODE_ALL

        // When
        val result = filter.shouldSync("com.example.app")

        // Then
        assertTrue(result)
    }

    @Test
    fun `shouldSync defaults to all mode when preference is missing`() {
        // Given
        every { preferences.getString(NotificationFilter.PREF_FILTER_MODE, any()) } returns null

        // When
        val result = filter.shouldSync("com.example.app")

        // Then
        assertTrue(result)
    }

    @Test
    fun `shouldSync returns true for whitelist mode when app is in whitelist`() {
        // Given
        every { preferences.getString(NotificationFilter.PREF_FILTER_MODE, any()) } returns
            NotificationFilter.FILTER_MODE_WHITELIST
        every { preferences.getStringSet(NotificationFilter.PREF_WHITELIST, any()) } returns
            setOf("com.example.app")

        // When
        val result = filter.shouldSync("com.example.app")

        // Then
        assertTrue(result)
    }

    @Test
    fun `shouldSync returns false for whitelist mode when app is not in whitelist`() {
        // Given
        every { preferences.getString(NotificationFilter.PREF_FILTER_MODE, any()) } returns
            NotificationFilter.FILTER_MODE_WHITELIST
        every { preferences.getStringSet(NotificationFilter.PREF_WHITELIST, any()) } returns
            setOf("com.other.app")

        // When
        val result = filter.shouldSync("com.example.app")

        // Then
        assertFalse(result)
    }

    @Test
    fun `shouldSync returns true for blacklist mode when app is not in blacklist`() {
        // Given
        every { preferences.getString(NotificationFilter.PREF_FILTER_MODE, any()) } returns
            NotificationFilter.FILTER_MODE_BLACKLIST
        every { preferences.getStringSet(NotificationFilter.PREF_BLACKLIST, any()) } returns
            setOf("com.other.app")

        // When
        val result = filter.shouldSync("com.example.app")

        // Then
        assertTrue(result)
    }

    @Test
    fun `shouldSync returns false for blacklist mode when app is in blacklist`() {
        // Given
        every { preferences.getString(NotificationFilter.PREF_FILTER_MODE, any()) } returns
            NotificationFilter.FILTER_MODE_BLACKLIST
        every { preferences.getStringSet(NotificationFilter.PREF_BLACKLIST, any()) } returns
            setOf("com.example.app")

        // When
        val result = filter.shouldSync("com.example.app")

        // Then
        assertFalse(result)
    }

    @Test
    fun `addToWhitelist should add app to whitelist`() {
        // Given
        every { preferences.getStringSet(NotificationFilter.PREF_WHITELIST, any()) } returns
            mutableSetOf()

        // When
        filter.addToWhitelist("com.example.app")

        // Then
        verify { editor.putStringSet(NotificationFilter.PREF_WHITELIST, any()) }
        verify { editor.apply() }
    }

    @Test
    fun `removeFromWhitelist should remove app from whitelist`() {
        // Given
        every { preferences.getStringSet(NotificationFilter.PREF_WHITELIST, any()) } returns
            mutableSetOf("com.example.app")

        // When
        filter.removeFromWhitelist("com.example.app")

        // Then
        verify { editor.putStringSet(NotificationFilter.PREF_WHITELIST, any()) }
        verify { editor.apply() }
    }

    @Test
    fun `addToBlacklist should add app to blacklist`() {
        // Given
        every { preferences.getStringSet(NotificationFilter.PREF_BLACKLIST, any()) } returns
            mutableSetOf()

        // When
        filter.addToBlacklist("com.example.app")

        // Then
        verify { editor.putStringSet(NotificationFilter.PREF_BLACKLIST, any()) }
        verify { editor.apply() }
    }

    @Test
    fun `setFilterMode should update filter mode`() {
        // When
        filter.setFilterMode(NotificationFilter.FILTER_MODE_ALL)

        // Then
        verify { editor.putString(NotificationFilter.PREF_FILTER_MODE, NotificationFilter.FILTER_MODE_ALL) }
        verify { editor.apply() }
    }
}
