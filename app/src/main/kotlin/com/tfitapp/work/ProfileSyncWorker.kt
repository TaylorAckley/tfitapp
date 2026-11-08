package com.tfitapp.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tfitapp.application.profile.PullProfileFromCloud
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Periodic catch-up for changes Claude made while this phone wasn't looking
 * (design basis §2 exception). Pushing on save already covers the other
 * direction, so this worker only pulls. A pull that finds nothing new, or
 * finds Supabase unreachable, is success — there is nothing to retry.
 */
@HiltWorker
class ProfileSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val pullProfile: PullProfileFromCloud,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        pullProfile()
        return Result.success()
    }
}
