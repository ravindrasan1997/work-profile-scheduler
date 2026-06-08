package com.worksched.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "worksched")

class ScheduleStore(private val context: Context) {
    private val scheduleKey = stringPreferencesKey("schedule_json")
    private val pendingResumeKey = booleanPreferencesKey("pending_resume")
    private val pendingResumeDeadlineKey = longPreferencesKey("pending_resume_deadline")
    private val json = Json { ignoreUnknownKeys = true }

    val scheduleFlow: Flow<Schedule> = context.dataStore.data.map { prefs ->
        prefs[scheduleKey]?.let { runCatching { json.decodeFromString<Schedule>(it) }.getOrNull() }
            ?: Schedule.default()
    }

    suspend fun save(schedule: Schedule) {
        context.dataStore.edit { it[scheduleKey] = json.encodeToString(schedule) }
    }

    // ---- pending-resume state (deferred resume while device locked) ----

    val pendingResumeFlow: Flow<Boolean> = context.dataStore.data.map { it[pendingResumeKey] ?: false }

    suspend fun pendingResume(): Boolean =
        context.dataStore.data.first()[pendingResumeKey] ?: false

    suspend fun pendingResumeDeadline(): Long =
        context.dataStore.data.first()[pendingResumeDeadlineKey] ?: 0L

    suspend fun setPendingResume(active: Boolean, deadlineMs: Long) {
        context.dataStore.edit {
            it[pendingResumeKey] = active
            it[pendingResumeDeadlineKey] = if (active) deadlineMs else 0L
        }
    }
}
