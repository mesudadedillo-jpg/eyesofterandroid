// Módulo de inyección de dependencias Koin que vincula HttpClient, ApiService, DataStore, Repository y ViewModel.
// Depende de mx.eyesofter.suite.network.*, mx.eyesofter.suite.data.local.* y mx.eyesofter.suite.ui.screens.resultado.*.
package mx.eyesofter.suite.di

import mx.eyesofter.suite.data.local.ResultadoLocalDataSource
import mx.eyesofter.suite.data.local.createDataStore
import mx.eyesofter.suite.data.repository.ResultadoRepository
import mx.eyesofter.suite.network.ApiService
import mx.eyesofter.suite.network.HttpClientFactory
import mx.eyesofter.suite.ui.screens.resultado.ResultadoViewModel
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val appModule = module {
    single { createDataStore(getOrNull()) }
    single { HttpClientFactory.create() }
    singleOf(::ApiService)
    singleOf(::ResultadoLocalDataSource)
    singleOf(::ResultadoRepository)
    viewModelOf(::ResultadoViewModel)
}
