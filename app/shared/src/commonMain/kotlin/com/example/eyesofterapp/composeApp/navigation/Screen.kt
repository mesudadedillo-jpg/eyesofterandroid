package com.example.eyesofterapp.composeApp.navigation

import com.example.eyesofterapp.domain.model.Role
import com.example.eyesofterapp.domain.model.User

/** Todas las pantallas "principales" posibles. Es una lista cerrada (sealed). */
sealed interface Screen {
    data object Login : Screen
    data class Admin(val user: User) : Screen
    data class Supervisor(val user: User) : Screen
    data class Cliente(val user: User) : Screen
    data class Doctor(val user: User) : Screen
    data class Vendedor(val user: User) : Screen
    data class Alumno(val user: User) : Screen
    data class Desconocido(val user: User) : Screen
}

/**
 * ENRUTAMIENTO POR ROL: recibe al usuario y devuelve a que pantalla ir segun su rol.
 * Como Screen es sealed, el compilador obliga a cubrir todos los roles en este "when".
 */
fun User.toScreen(): Screen = when (role) {
    Role.ADMINISTRADOR -> Screen.Admin(this)
    Role.SUPERVISOR -> Screen.Supervisor(this)
    Role.CLIENTE -> Screen.Cliente(this)
    Role.DOCTOR -> Screen.Doctor(this)
    Role.VENDEDOR -> Screen.Vendedor(this)
    Role.ALUMNO -> Screen.Alumno(this)
    Role.DESCONOCIDO -> Screen.Desconocido(this)
}