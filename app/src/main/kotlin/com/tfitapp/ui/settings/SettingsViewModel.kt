package com.tfitapp.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tfitapp.application.port.SecretKey
import com.tfitapp.application.port.SecretStore
import com.tfitapp.application.port.SettingsRepository
import com.tfitapp.application.profile.ProfileSyncPort
import com.tfitapp.application.profile.PullProfileFromCloud
import com.tfitapp.application.profile.SyncProfileToCloud
import com.tfitapp.application.profile.SyncResult
import com.tfitapp.application.settings.HomeLocation
import com.tfitapp.application.settings.UserSettings
import com.tfitapp.domain.nutrition.BodyProfile
import com.tfitapp.domain.nutrition.GoalPhase
import com.tfitapp.domain.nutrition.GoalSettings
import com.tfitapp.domain.nutrition.Sex
import com.tfitapp.domain.timer.CoursePoint
import com.tfitapp.domain.timer.LinesCourse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class KeysState(val typesafeSet: Boolean = false, val fdcSet: Boolean = false)

/** Design basis §2 exception: whether this phone can reach Supabase for bodyspec/goal sync. */
data class SupabaseState(
    val urlSet: Boolean = false,
    val anonKeySet: Boolean = false,
    val signedIn: Boolean = false,
    val syncing: Boolean = false,
    val error: String? = null,
) {
    val configured: Boolean get() = urlSet && anonKeySet
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val profileSync: ProfileSyncPort,
    private val pushProfile: SyncProfileToCloud,
    private val pullProfile: PullProfileFromCloud,
) : ViewModel() {

    val state: StateFlow<UserSettings> = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

    val keys: StateFlow<KeysState> = combine(secrets.observeIsSet(SecretKey.TYPESAFE_API_KEY), secrets.observeIsSet(SecretKey.FDC_API_KEY)) { a, b ->
        KeysState(a, b)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), KeysState())

    private val supabaseStateInternal = MutableStateFlow(SupabaseState())
    val supabase: StateFlow<SupabaseState> = combine(
        secrets.observeIsSet(SecretKey.SUPABASE_URL),
        secrets.observeIsSet(SecretKey.SUPABASE_ANON_KEY),
        secrets.observeIsSet(SecretKey.SUPABASE_ACCESS_TOKEN),
        supabaseStateInternal,
    ) { urlSet, anonSet, signedIn, transient ->
        transient.copy(urlSet = urlSet, anonKeySet = anonSet, signedIn = signedIn)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SupabaseState())

    fun saveKey(key: SecretKey, value: String) = viewModelScope.launch { secrets.set(key, value) }
    fun clearKey(key: SecretKey) = viewModelScope.launch { secrets.set(key, null) }

    /** Signs in to the user's own Supabase project, then immediately pulls whatever profile is already there. */
    fun signInToSupabase(email: String, password: String) = viewModelScope.launch {
        supabaseStateInternal.update { it.copy(syncing = true, error = null) }
        val ok = profileSync.signIn(email, password)
        if (ok) syncProfileNow() else supabaseStateInternal.update { it.copy(syncing = false, error = "Sign-in failed") }
    }

    /** Pushes this phone's body/goal/schedule, then pulls back whatever is now authoritative. Never blocks the screen. */
    fun syncProfileNow() = viewModelScope.launch { doSync() }

    private suspend fun doSync() {
        supabaseStateInternal.update { it.copy(syncing = true, error = null) }
        val pushResult = pushProfile()
        val pullResult = pullProfile()
        val error = (pushResult as? SyncResult.Unavailable)?.reason ?: (pullResult as? SyncResult.Unavailable)?.reason
        supabaseStateInternal.update { it.copy(syncing = false, error = error) }
    }

    fun saveBody(sex: Sex, age: Int, heightCm: Double, weightKg: Double, activity: Double) = update(syncAfter = true) {
        it.copy(body = BodyProfile(sex, age, heightCm, weightKg, activity))
    }

    fun setPhase(phase: GoalPhase) = update(syncAfter = true) { it.copy(goal = GoalSettings(phase)) }
    fun setGoalNumbers(offset: Int, proteinPerKg: Double) = update(syncAfter = true) { it.copy(goal = it.goal.copy(budgetOffsetKcal = offset, proteinPerKg = proteinPerKg)) }

    fun toggleEquipment(id: String, present: Boolean) = update { s ->
        s.copy(equipment = s.equipment.map { if (it.id == id) it.copy(present = present) else it })
    }

    fun saveHome(lat: Double, lon: Double) = update { it.copy(home = HomeLocation(lat, lon)) }
    fun clearHome() = update { it.copy(home = null) }

    fun saveTraining(timeBudget: Int, startHour: Int, sledMax: Int) = update(syncAfter = true) {
        it.copy(timeBudgetMinutes = timeBudget, trainingStartHour = startHour.coerceIn(0, 23), sledMaxSetting = sledMax.coerceAtLeast(1))
    }

    /** Distances in metres, nearest first. Blank list clears the course. */
    fun saveCourse(metres: List<Int>) = update { s ->
        val clean = metres.filter { it > 0 }.sorted().distinct()
        s.copy(
            linesCourse = if (clean.isEmpty()) null else LinesCourse(
                id = s.linesCourse?.id ?: "course.home",
                name = "Home",
                points = clean.mapIndexed { i, m -> CoursePoint(i, "P${i + 1}", m) },
            ),
        )
    }

    private fun update(syncAfter: Boolean = false, transform: (UserSettings) -> UserSettings) = viewModelScope.launch {
        settings.update(transform)
        if (syncAfter) doSync()
    }
}
