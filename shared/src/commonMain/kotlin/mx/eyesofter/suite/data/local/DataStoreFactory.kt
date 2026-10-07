// Declaración multiplataforma (expect) para construir la instancia de DataStore Preferences.
// Se complementa con las implementaciones actual en DataStoreFactory.android.kt, DataStoreFactory.ios.kt y DataStoreFactory.jvm.kt.
package mx.eyesofter.suite.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences

expect fun createDataStore(context: Any? = null): DataStore<Preferences>

internal const val DATASTORE_FILE_NAME = "eyesofter_preferences.preferences_pb"
