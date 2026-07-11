package app.murmurnote.android.data.preference

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.murmurnote.android.data.remote.llm.LlmProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppPreferencesDataStoreTest {

    @Test
    fun safeLexiconRequiresExplicitEnableAndDisablesWhenTheActiveApiBecomesUnavailable() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val file = File(
                context.cacheDir,
                "datastore-test-${UUID.randomUUID()}.preferences_pb",
            )
            val store = PreferenceDataStoreFactory.create(scope = scope) { file }
            val preferences = AppPreferences(store)

            try {
                preferences.setLlmProvider(LlmProvider.DEEPSEEK)
                preferences.setLlmApiKey(LlmProvider.DEEPSEEK, "")

                assertFalse(preferences.setSafeLexiconEnabled(true))
                assertFalse(preferences.safeLexiconEnabled.first())

                preferences.setLlmApiKey(LlmProvider.DEEPSEEK, "test-key")
                assertFalse(preferences.safeLexiconEnabled.first())

                assertTrue(preferences.setSafeLexiconEnabled(true))
                assertTrue(preferences.safeLexiconEnabled.first())

                preferences.setLlmApiKey(LlmProvider.DEEPSEEK, "")
                assertFalse(preferences.safeLexiconEnabled.first())

                preferences.setLlmApiKey(LlmProvider.DEEPSEEK, "restored-key")
                preferences.setSafeLexiconEnabled(true)
                preferences.setLlmProvider(LlmProvider.ANTHROPIC)
                assertFalse(preferences.safeLexiconEnabled.first())
            } finally {
                scope.cancel()
                file.delete()
            }
        }
}
