// Implementación específica de iOS (actual) para instanciar DataStore Preferences en la ruta de Documentos de Apple.
// Depende de la función expect definida en mx.eyesofter.suite.data.local.DataStoreFactory.
package mx.eyesofter.suite.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask
import okio.Path.Companion.toPath

@OptIn(ExperimentalForeignApi::class)
actual fun createDataStore(context: Any?): DataStore<Preferences> {
    val documentDirectory = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = false,
        error = null
    )
    val path = requireNotNull(documentDirectory?.path) + "/$DATASTORE_FILE_NAME"
    return PreferenceDataStoreFactory.createWithPath(
        produceFile = { path.toPath() }
    )
}
