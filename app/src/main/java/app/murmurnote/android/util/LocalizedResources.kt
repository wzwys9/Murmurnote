package app.murmurnote.android.util

import android.content.Context
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat

/** Returns resources using the app locale even when the receiver is an Application or Service. */
fun Context.localizedString(
    @StringRes resourceId: Int,
    vararg formatArgs: Any,
): String {
    // AppCompat locales only wrap Activity contexts before Android 13. AndroidX provides this
    // localized context for ViewModels, services, widgets, and other application-scoped callers.
    // Source: https://developer.android.com/guide/topics/resources/app-languages
    val localizedContext = ContextCompat.getContextForLanguage(this)
    return if (formatArgs.isEmpty()) {
        localizedContext.getString(resourceId)
    } else {
        localizedContext.getString(resourceId, *formatArgs)
    }
}
