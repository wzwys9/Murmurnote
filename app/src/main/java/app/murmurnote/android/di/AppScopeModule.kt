package app.murmurnote.android.di

import app.murmurnote.android.util.Logger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppScopeModule {
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(logger: Logger): CoroutineScope {
        val handler = CoroutineExceptionHandler { context, throwable ->
            logger.e(
                "AppScope",
                "uncaught coroutine exception",
                fields = mapOf("context" to context.toString()),
                tr = throwable
            )
        }
        return CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)
    }
}
