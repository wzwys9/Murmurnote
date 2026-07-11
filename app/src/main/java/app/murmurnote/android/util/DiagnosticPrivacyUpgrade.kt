package app.murmurnote.android.util

import java.io.File

/** Clears pre-metadata-only runtime logs once, before marking the privacy upgrade complete. */
object DiagnosticPrivacyUpgrade {
    private const val MARKER_RELATIVE_PATH = "privacy/metadata_only_diagnostics_v1"

    /** Returns true when the upgrade ran, or false when it had already completed. */
    @Synchronized
    fun apply(filesDir: File, clearLegacyLogs: () -> Unit): Boolean {
        val marker = File(filesDir, MARKER_RELATIVE_PATH)
        if (marker.isFile) return false

        clearLegacyLogs()

        val parent = marker.parentFile
            ?: error("Diagnostic privacy marker has no parent directory")
        check(parent.mkdirs() || parent.isDirectory) {
            "Unable to create diagnostic privacy marker directory"
        }
        val temporaryMarker = File(parent, "${marker.name}.tmp")
        temporaryMarker.writeText("1\n", Charsets.UTF_8)
        if (!temporaryMarker.renameTo(marker)) {
            if (marker.isFile) {
                temporaryMarker.delete()
            } else {
                temporaryMarker.delete()
                error("Unable to commit diagnostic privacy marker")
            }
        }
        return true
    }
}
