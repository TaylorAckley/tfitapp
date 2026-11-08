package com.tfitapp.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tfitapp.application.nutrition.ObserveWeeklyReview
import com.tfitapp.domain.nutrition.WeeklyReview
import com.tfitapp.domain.time.WallClock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class ReviewViewModel @Inject constructor(observe: ObserveWeeklyReview, clock: WallClock, zone: ZoneId) : ViewModel() {
    val review: StateFlow<WeeklyReview?> =
        observe(clock.now().atZone(zone).toLocalDate()).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
