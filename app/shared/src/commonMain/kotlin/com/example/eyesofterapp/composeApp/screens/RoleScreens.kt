package com.example.eyesofterapp.composeApp.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.eyesofterapp.domain.model.User

/**
 * Estructura comun de TODAS las pantallas por rol: barra superior (con boton "Salir"),
 * saludo y rol. Cada pantalla solo escribe su contenido propio en la lambda "content".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoleScaffold(
    title: String,
    user: User,
    onLogout: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    TextButton(onClick = onLogout) {
                        Text("Salir", color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Bienvenido, ${user.fullName}", style = MaterialTheme.typography.titleLarge)
            Text("Rol: ${user.role.name}", style = MaterialTheme.typography.bodyMedium)
            content()
        }
    }
}

/** Tarjeta simple reutilizable para mostrar secciones. */
@Composable
fun InfoCard(title: String, description: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun AdminScreen(user: User, onLogout: () -> Unit) =
    RoleScaffold("Panel de Administración", user, onLogout) {
        InfoCard("Dashboard", "Alumnos evaluados, casos abiertos y tiempo promedio de atención.")
        InfoCard("Usuarios", "Alta, edición y baja de usuarios del sistema.")
        InfoCard("Clínicas", "Directorio de consultorios y ópticas asociadas.")
    }

@Composable
fun SupervisorScreen(user: User, onLogout: () -> Unit) =
    RoleScaffold("Panel del Supervisor", user, onLogout) {
        InfoCard("Casos abiertos", "Seguimiento de los casos pendientes por grado y grupo.")
        InfoCard("Comprobantes", "Validar o rechazar los comprobantes enviados.")
    }

@Composable
fun ClienteScreen(user: User, onLogout: () -> Unit) =
    RoleScaffold("Mi espacio", user, onLogout) {
        InfoCard("Reporte", "Resultado en formato semáforo, en lenguaje claro.")
        InfoCard("Directorio", "Clínicas y ópticas cercanas.")
        InfoCard("Comprobantes", "Sube la receta o el comprobante de lentes.")
    }

@Composable
fun DoctorScreen(user: User, onLogout: () -> Unit) =
    RoleScaffold("Panel del Doctor", user, onLogout) {
        InfoCard("Escanear pase", "Lee el código QR del pase médico del paciente.")
        InfoCard("Atenciones", "Registro de las atenciones brindadas.")
    }

@Composable
fun AlumnoScreen(user: User, onLogout: () -> Unit) =
    RoleScaffold("Cuida tu vista", user, onLogout) {
        InfoCard("Regla 20-20-20", "Cada 20 minutos, mira algo a 20 pies (6 m) durante 20 segundos.")
        InfoCard("Ejercicios", "Parpadeo, enfoque y relajación ocular.")
    }