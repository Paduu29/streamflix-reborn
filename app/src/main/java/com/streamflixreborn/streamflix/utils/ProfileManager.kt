package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.database.ProfileDatabase
import com.streamflixreborn.streamflix.database.dao.ProfileDao
import com.streamflixreborn.streamflix.models.Profile
import kotlinx.coroutines.flow.Flow
import java.util.UUID

object ProfileManager {

    private const val TAG = "ProfileManager"
    private const val GLOBAL_PREFS_NAME = "${BuildConfig.APPLICATION_ID}.profile_global"
    private const val KEY_ACTIVE_PROFILE_ID = "ACTIVE_PROFILE_ID"
    private const val DEFAULT_PROFILE_ID = "default"

    private lateinit var appContext: Context
    private var profileDao: ProfileDao? = null
    private var _activeProfile: Profile? = null

    val activeProfile: Profile? get() = _activeProfile
    val activeProfileId: String? get() = _activeProfile?.id

    private val globalPrefs: SharedPreferences?
        get() = if (::appContext.isInitialized)
            appContext.getSharedPreferences(GLOBAL_PREFS_NAME, Context.MODE_PRIVATE) else null

    init {
        UserPreferences._profileManagerReady = true
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext

        val db = ProfileDatabase.getInstance(appContext)
        profileDao = db.profileDao()

        val storedId = globalPrefs?.getString(KEY_ACTIVE_PROFILE_ID, null)

        if (storedId == null) {
            createDefaultProfile()
        } else {
            val profile = runCatching {
                kotlinx.coroutines.runBlocking {
                    profileDao?.getProfileById(storedId)
                }
            }.getOrNull()

            if (profile == null) {
                Log.w(TAG, "Stored profile $storedId not found, creating default")
                createDefaultProfile()
            } else {
                _activeProfile = profile
            }
        }

        applyActiveProfilePrefs()
        Log.i(TAG, "Initialized. Active profile: ${_activeProfile?.name} (${_activeProfile?.id})")
    }

    private fun createDefaultProfile() {
        val defaultProfile = Profile(
            id = DEFAULT_PROFILE_ID,
            name = "Default",
            position = 0,
        )
        kotlinx.coroutines.runBlocking {
            profileDao?.insert(defaultProfile)
        }

        _activeProfile = defaultProfile
        globalPrefs?.edit()?.putString(KEY_ACTIVE_PROFILE_ID, defaultProfile.id)?.apply()

        migrateLegacyPrefs()
        Log.i(TAG, "Created default profile: ${defaultProfile.name}")
    }

    private fun migrateLegacyPrefs() {
        val legacyPrefs = appContext.getSharedPreferences(
            "${BuildConfig.APPLICATION_ID}.preferences",
            Context.MODE_PRIVATE,
        )

        val profilePrefs = getProfilePrefs(DEFAULT_PROFILE_ID)
        profilePrefs.edit().apply {
            legacyPrefs.all.forEach { (key, value) ->
                when (value) {
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Set<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        putStringSet(key, value as Set<String>)
                    }
                    else -> {}
                }
            }
            commit()
        }

        Log.i(TAG, "Migrated ${legacyPrefs.all.size} legacy preferences to profile: $DEFAULT_PROFILE_ID")
    }

    fun switchToProfile(profileId: String, preserveProvider: Boolean = true) {
        kotlinx.coroutines.runBlocking {
            val profile = profileDao?.getProfileById(profileId)
            if (profile == null) {
                Log.e(TAG, "Cannot switch to non-existent profile: $profileId")
                return@runBlocking
            }

            val currentProviderName = if (preserveProvider) UserPreferences.getCurrentProviderName() else null

            AppDatabase.resetInstance()
            UserDataCache.clearAll(appContext)

            _activeProfile = profile
            globalPrefs?.edit()?.putString(KEY_ACTIVE_PROFILE_ID, profileId)?.apply()

            applyActiveProfilePrefs()

            if (preserveProvider && currentProviderName != UserPreferences.getCurrentProviderName()) {
                UserPreferences.setCurrentProviderName(currentProviderName)
            }
            Log.i(TAG, "Switched to profile: ${profile.name} (${profile.id})")
        }
    }

    private fun applyActiveProfilePrefs() {
        val profileId = _activeProfile?.id ?: return
        val profilePrefs = getProfilePrefs(profileId)
        UserPreferences.profilePrefs = profilePrefs
    }

    fun getProfilePrefs(profileId: String): SharedPreferences {
        return appContext.getSharedPreferences(
            "${BuildConfig.APPLICATION_ID}.preferences_${profileId}",
            Context.MODE_PRIVATE,
        )
    }

    fun getAllProfilesFlow(): Flow<List<Profile>>? = profileDao?.getAllProfiles()

    suspend fun getAllProfiles(): List<Profile> = profileDao?.getAllProfilesList() ?: emptyList()

    suspend fun getProfileById(id: String): Profile? = profileDao?.getProfileById(id)

    suspend fun createProfile(name: String): Profile? {
        val pos = profileDao?.getNextPosition() ?: return null
        val profile = Profile(
            name = name.trim().take(30),
            position = pos,
        )
        profileDao?.insert(profile)
        Log.i(TAG, "Created profile: ${profile.name} (${profile.id})")
        return profile
    }

    suspend fun renameProfile(id: String, newName: String): Boolean {
        val profile = profileDao?.getProfileById(id) ?: return false
        val updated = profile.copy(name = newName.trim().take(30))
        profileDao?.update(updated)
        if (id == _activeProfile?.id) {
            _activeProfile = updated
        }
        Log.i(TAG, "Renamed profile $id to: $newName")
        return true
    }

    suspend fun deleteProfile(id: String): Boolean {
        val allProfiles = profileDao?.getAllProfilesList() ?: return false
        if (allProfiles.size <= 1) return false

        val profile = profileDao?.getProfileById(id) ?: return false
        profileDao?.delete(profile)

        val profilePrefs = getProfilePrefs(id)
        profilePrefs.edit().clear().commit()

        if (id == _activeProfile?.id) {
            val next = allProfiles.firstOrNull { it.id != id }
            if (next != null) switchToProfile(next.id)
        }

        Log.i(TAG, "Deleted profile: $id")
        return true
    }

    suspend fun getProfileCount(): Int = profileDao?.getProfileCount() ?: 1
}
