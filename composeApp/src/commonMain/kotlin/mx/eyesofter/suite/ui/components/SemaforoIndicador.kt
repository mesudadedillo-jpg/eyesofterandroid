// Componente de interfaz gráfica que muestra el indicador de semáforo con color, símbolo e iconografía clara.
// Depende de mx.eyesofter.suite.model.Semaforo y los colores definidos en mx.eyesofter.suite.ui.theme.*.
package mx.eyesofter.suite.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mx.eyesofter.suite.model.Semaforo
import mx.eyesofter.suite.ui.theme.SemaforoAmarilloNaranja
import mx.eyesofter.suite.ui.theme.SemaforoRojo
import mx.eyesofter.suite.ui.theme.SemaforoVerde
import mx.eyesofter.suite.ui.theme.TealPrimary

@Composable
fun SemaforoIndicador(
    estado: Semaforo,
    modifier: Modifier = Modifier
) {
    val (colorActivo, simbolo, texto) = when (estado) {
        Semaforo.VERDE -> Triple(SemaforoVerde, "✓", "Semáforo Verde - Visualmente Saludable")
        Semaforo.AMARILLO -> Triple(SemaforoAmarilloNaranja, "!", "Semáforo Amarillo - Requiere Seguimiento")
        Semaforo.ROJO -> Triple(SemaforoRojo, "✕", "Semáforo Rojo - Atención Médica Requerida")
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(TealPrimary.copy(alpha = 0.08f))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LuzSemaforo(
                color = SemaforoVerde,
                estaActivo = estado == Semaforo.VERDE
            )
            LuzSemaforo(
                color = SemaforoAmarilloNaranja,
                estaActivo = estado == Semaforo.AMARILLO
            )
            LuzSemaforo(
                color = SemaforoRojo,
                estaActivo = estado == Semaforo.ROJO
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(colorActivo),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = simbolo,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
            Text(
                text = texto,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colorActivo
            )
        }
    }
}

@Composable
private fun LuzSemaforo(
    color: Color,
    estaActivo: Boolean
) {
    val alpha = if (estaActivo) 1.0f else 0.20f
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha))
    )
}
