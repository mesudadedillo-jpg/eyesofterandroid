// Implementación específica de Android (actual) para instanciar DataStore Preferences usando el Context de la aplicación.
// Depende de la función expect definida en mx.eyesofter.suite.data.local.DataStoreFactory.
package mx.eyesofter.suite.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import okio.Path.Companion.toPath

actual fun createDataStore(context: Any?): DataStore<Preferences> {
    requireNotNull(context) { "Android Context is required for DataStore initialization" }
    val appContext = (context as Context).applicationContext
    return PreferenceDataStoreFactory.createWithPath(
        produceFile = { appContext.filesDir.resolve(DATASTORE_FILE_NAME).absolutePath.toPath() }
    )
}
