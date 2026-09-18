package nl.tippie.subtitle.ui.navigation

import kotlinx.serialization.Serializable

@Serializable object HomeRoute
@Serializable data class ImportRoute(val uri: String)
@Serializable data class ProcessingRoute(val projectId: Long)
@Serializable data class EditorRoute(val projectId: Long)
@Serializable data class ExportRoute(val projectId: Long)
@Serializable object SettingsRoute
