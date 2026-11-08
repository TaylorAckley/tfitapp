package com.tfitapp.di

import android.content.Context
import android.os.SystemClock
import com.tfitapp.application.judgement.JudgementPort
import com.tfitapp.application.port.ExerciseRepository
import com.tfitapp.application.port.FoodLogRepository
import com.tfitapp.application.port.FoodLookupPort
import com.tfitapp.application.port.FoodRepository
import com.tfitapp.application.port.MealTemplateRepository
import com.tfitapp.application.port.ForecastPort
import com.tfitapp.application.profile.ProfileSyncPort
import com.tfitapp.application.port.SecretStore
import com.tfitapp.application.port.SessionRepository
import com.tfitapp.application.port.SettingsRepository
import com.tfitapp.application.port.TimerRunRepository
import com.tfitapp.application.port.WeightRepository
import com.tfitapp.domain.time.MonotonicClock
import com.tfitapp.domain.time.WallClock
import com.tfitapp.infrastructure.fdc.FdcFoodLookup
import com.tfitapp.infrastructure.nws.NwsForecastClient
import com.tfitapp.infrastructure.room.TFitDatabase
import com.tfitapp.infrastructure.room.repo.RoomExerciseRepository
import com.tfitapp.infrastructure.room.repo.RoomFoodLogRepository
import com.tfitapp.infrastructure.room.repo.RoomFoodRepository
import com.tfitapp.infrastructure.room.repo.RoomMealTemplateRepository
import com.tfitapp.infrastructure.room.repo.RoomSessionRepository
import com.tfitapp.infrastructure.room.repo.RoomSettingsRepository
import com.tfitapp.infrastructure.room.repo.RoomTimerRunRepository
import com.tfitapp.infrastructure.room.repo.RoomWeightRepository
import com.tfitapp.infrastructure.supabase.SupabaseProfileClient
import com.tfitapp.infrastructure.typesafe.TypeSafeJudgementClient
import com.tfitapp.secrets.KeystoreSecretStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import java.time.Instant
import java.time.ZoneId
import javax.inject.Singleton

/**
 * The one place outer implementations meet the ports. Each `:infrastructure:*`
 * module gets its own Ktor engine instance so timeouts and headers never leak
 * between APIs.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton fun monotonicClock(): MonotonicClock = MonotonicClock { SystemClock.elapsedRealtime() }
    @Provides @Singleton fun wallClock(): WallClock = WallClock { Instant.now() }
    @Provides fun zoneId(): ZoneId = ZoneId.systemDefault()

    @Provides @Singleton fun database(@ApplicationContext ctx: Context): TFitDatabase = TFitDatabase.build(ctx)

    @Provides @Singleton fun secretStore(@ApplicationContext ctx: Context): SecretStore = KeystoreSecretStore(ctx)

    @Provides @Singleton fun foodRepository(db: TFitDatabase, @ApplicationContext ctx: Context): FoodRepository = RoomFoodRepository(db.foods(), ctx)
    @Provides @Singleton fun foodLogRepository(db: TFitDatabase): FoodLogRepository = RoomFoodLogRepository(db.foodLog())
    @Provides @Singleton fun weightRepository(db: TFitDatabase): WeightRepository = RoomWeightRepository(db.weights())
    @Provides @Singleton fun settingsRepository(db: TFitDatabase): SettingsRepository = RoomSettingsRepository(db.settings())
    @Provides @Singleton fun exerciseRepository(db: TFitDatabase): ExerciseRepository = RoomExerciseRepository(db.exercises())
    @Provides @Singleton fun sessionRepository(db: TFitDatabase): SessionRepository = RoomSessionRepository(db.sessions())
    @Provides @Singleton fun timerRunRepository(db: TFitDatabase): TimerRunRepository = RoomTimerRunRepository(db.timerRuns())
    @Provides @Singleton fun mealTemplateRepository(db: TFitDatabase): MealTemplateRepository = RoomMealTemplateRepository(db.mealTemplates())

    @Provides @Singleton fun judgementPort(secrets: SecretStore): JudgementPort = TypeSafeJudgementClient(engine(), secrets)
    @Provides @Singleton fun foodLookupPort(secrets: SecretStore): FoodLookupPort = FdcFoodLookup(engine(), secrets)
    @Provides @Singleton fun forecastPort(clock: WallClock): ForecastPort = NwsForecastClient(engine(), clock)
    @Provides @Singleton fun profileSyncPort(secrets: SecretStore): ProfileSyncPort = SupabaseProfileClient(engine(), secrets)

    private fun engine(): HttpClientEngine = OkHttp.create()
}
