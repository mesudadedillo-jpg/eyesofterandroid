// Fuente de datos local que guarda y recupera el último ResultadoTamizaje persistido en DataStore Preferences como JSON.
// Depende del modelo mx.eyesofter.suite.model.ResultadoTamizaje y DataStore.
package mx.eyesofter.suite.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mx.eyesofter.suite.model.ResultadoTamizaje

class ResultadoLocalDataSource(
    private val dataStore: DataStore<Preferences>
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val keyResultado = stringPreferencesKey("ultimo_resultado_tamizaje")

    val ultimoResultado: Flow<ResultadoTamizaje?> = dataStore.data.map { preferences ->
        preferences[keyResultado]?.let { jsonString ->
            runCatching {
                json.decodeFromString<ResultadoTamizaje>(jsonString)
            }.getOrNull()
        }
    }

    suspend fun obtenerUltimoResultado(): ResultadoTamizaje? {
        return ultimoResultado.firstOrNull()
    }

    suspend fun guardarResultado(resultado: ResultadoTamizaje) {
        dataStore.edit { preferences ->
            preferences[keyResultado] = json.encodeToString(resultado)
        }
    }
}
