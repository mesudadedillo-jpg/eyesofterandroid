// Implementación específica de JVM (actual) para instanciar DataStore Preferences en el directorio del usuario.
// Depende de la función expect definida en mx.eyesofter.suite.data.local.DataStoreFactory.
package mx.eyesofter.suite.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import okio.Path.Companion.toPath
import java.io.File

actual fun createDataStore(context: Any?): DataStore<Preferences> {
    val file = File(System.getProperty("user.home"), ".eyesofter/$DATASTORE_FILE_NAME")
    file.parentFile?.mkdirs()
    return PreferenceDataStoreFactory.createWithPath(
        produceFile = { file.absolutePath.toPath() }
    )
}
