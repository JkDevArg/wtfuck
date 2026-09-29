package com.wtfuck.app.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * Tokens de color de wtfuck, en dos temas.
 *
 * ## Por que el tema claro tardo, y que hizo falta para tenerlo
 *
 * Durante todo el proyecto el tema claro estuvo declarado como "fuera de
 * alcance", y la razon era real: el cian de marca `#6CF8F6` da **1.28:1 sobre
 * blanco**. Como texto es invisible; como relleno con texto blanco encima,
 * tambien. No era pereza, era que la paleta de marca es una escala pensada para
 * fondo oscuro y no se puede reusar tal cual.
 *
 * Lo que hizo falta fue **rederivar los acentos** para fondo claro, midiendo en
 * vez de elegir a ojo. Los tres acentos claros de aqui abajo son los primeros
 * valores que pasan 4.5:1 contra las tres superficies claras **y** 4.5:1 con
 * texto blanco encima cuando se usan como relleno. Los contrastes que van en los
 * comentarios estan calculados, no estimados.
 *
 * El resultado es que el tema claro no es "el oscuro con los colores dados
 * vuelta": es otra escala con el mismo significado. El cian sigue queriendo
 * decir "va bien", el ambar "esperando" y el coral "se rompio".
 *
 * ## Por que los tokens son getters y no constantes
 *
 * Porque hay del orden de mil usos de `Cian`, `BgBase` y compania repartidos por
 * cincuenta archivos, escritos como nombres sueltos y no como campos de un
 * objeto. Convertirlos a un `CompositionLocal` —que seria lo canonico— es un
 * cambio mecanico en cada uno de esos mil sitios: mucho riesgo para ningun
 * beneficio nuevo.
 *
 * Un getter que lee un `mutableStateOf` **si** participa de la observacion de
 * Compose: leerlo durante la composicion registra la lectura, y cambiar el modo
 * recompone todo lo que lo leyo. Funciona igual y no toca ni una linea de las
 * pantallas.
 *
 * El precio, dicho: estos tokens ya no son constantes de compilacion y no se
 * pueden usar donde haga falta una (no hacia falta en ningun sitio), y leerlos
 * fuera de la composicion devuelve el valor del momento, que es lo correcto para
 * los dos usos que hay asi —una notificacion y un icono—.
 */

/** Qué tema esta pintando ahora mismo. Lo fija [WtfuckTheme]. */
internal var claro by mutableStateOf(false)

// ---------------------------------------------------------------
//  Marca, tema OSCURO
// ---------------------------------------------------------------
//
// Los cinco colores de marca son ACENTOS, no una escala completa. Los dos
// neutros de la paleta (#677878 y #787067) miden 3.69:1 y 3.51:1 sobre la
// superficie base: NO alcanzan el minimo 4.5:1 de WCAG AA para texto. Por eso
// aqui se usan solo como bordes y superficies.
//
// Todos los contrastes del tema oscuro estan medidos contra BgSurface (#161D1D).

/** Primario oscuro. Burbuja propia, boton principal, "conectado". 13.36:1 */
private val CianOsc = Color(0xFF6CF8F6)

/** Pendiente / encolado / sin red. El color de `msg off`. 9.73:1 */
private val AmbarOsc = Color(0xFFF7B76D)

/** Error, destructivo, identidad sin verificar. 7.04:1 */
private val CoralOsc = Color(0xFFF7876D)

/** Borde, divisor, contorno de burbuja ajena. NO usar como texto. 3.69:1 */
private val SlateOsc = Color(0xFF677878)

/** Superficie alterna, control deshabilitado. NO usar como texto. 3.51:1 */
private val TaupeOsc = Color(0xFF787067)

private val BgBaseOsc = Color(0xFF0E1313)
private val BgSurfaceOsc = Color(0xFF161D1D)
private val BgElevOsc = Color(0xFF1F2828)

/** Cuerpo del mensaje, titulos. 13.15:1 */
private val TextoPrimarioOsc = Color(0xFFDCE3E3)

/** Ultimo mensaje, nombres secundarios. 6.43:1 */
private val TextoSecundarioOsc = Color(0xFF95A1A1)

/** Marcas de tiempo, metadatos. 4.90:1 — el minimo aceptable. */
private val TextoTerciarioOsc = Color(0xFF7E8C8C)

/** Texto SOBRE los acentos. Oscuro, porque blanco sobre cian da 1.28:1. */
private val TextoSobreAcentoOsc = Color(0xFF0E1313)

// ---------------------------------------------------------------
//  Marca, tema CLARO — rederivada, con los contrastes medidos
// ---------------------------------------------------------------
//
// Los tres contrastes de cada acento son, en orden: contra BgBase (#E8EFEF),
// contra BgSurface (#FFFFFF) y contra BgElev (#F7FAFA). El cuarto numero es el
// del texto BLANCO encima, que es como se usan de relleno.

/** Primario claro. 5.11 / 6.06 / 5.86 · blanco encima 6.06:1 */
private val CianCla = Color(0xFF0B6E6D)

/** Pendiente. 5.33 / 6.33 / 6.12 · blanco encima 6.33:1 */
private val AmbarCla = Color(0xFF8A5300)

/** Error, destructivo. 5.28 / 6.26 / 6.05 · blanco encima 6.26:1 */
private val CoralCla = Color(0xFFB3301A)

/**
 * Borde y divisor. 3.02 / 3.42 / 3.32
 *
 * Pasa el 3:1 que WCAG pide para **componentes**, que es el umbral que
 * corresponde: no es texto. El gris mas claro que se habia probado primero
 * (#A8B8B8) daba 2.06 y en una pantalla al sol las tarjetas desaparecian.
 */
private val SlateCla = Color(0xFF7E8E8E)

private val TaupeCla = Color(0xFF8E8478)

/**
 * Fondo de la app. No es blanco a proposito.
 *
 * En el tema oscuro las tarjetas se distinguen del fondo porque son mas claras.
 * En claro, si el fondo fuera blanco, una tarjeta blanca sin sombra seria
 * invisible: este diseno no usa sombras. Con #E8EFEF la tarjeta blanca queda a
 * 1.165 del fondo, que sin sombra es lo que hace que se vea el borde del bloque.
 */
private val BgBaseCla = Color(0xFFE8EFEF)

private val BgSurfaceCla = Color(0xFFFFFFFF)

/** Hojas modales, menus, burbuja ajena. Un pelo frio para separarla del fondo. */
private val BgElevCla = Color(0xFFF7FAFA)

/** Cuerpo del mensaje, titulos. 15.32 / 18.18 / 17.61 */
private val TextoPrimarioCla = Color(0xFF0F1717)

/** Ultimo mensaje, nombres secundarios. 7.80 / 9.25 / 8.96 */
private val TextoSecundarioCla = Color(0xFF3C4A4A)

/** Marcas de tiempo, metadatos. 5.14 / 6.10 / 5.91 */
private val TextoTerciarioCla = Color(0xFF566565)

/**
 * Texto SOBRE los acentos. Aqui es BLANCO, al revES que en oscuro.
 *
 * Es el cambio que hace que el tema claro no sea "el oscuro invertido": en
 * oscuro el acento es luminoso y pide tinta oscura; en claro el acento es
 * oscuro y pide tinta blanca. Un solo valor para los dos temas dejaria
 * ilegible la mitad de los botones.
 */
private val TextoSobreAcentoCla = Color(0xFFFFFFFF)

// ---------------------------------------------------------------
//  Paletas de acento
// ---------------------------------------------------------------

/**
 * El color de acento se puede elegir. El resto de la paleta, no.
 *
 * ## Que cambia y que NO
 *
 * Cambia **solo el primario**: la burbuja propia, los botones, "conectado".
 * `Ambar` y `Coral` se quedan fijos porque no son decoracion, son
 * **significado**: ambar es "pendiente" y coral es "error". Si el acento
 * elegido fuera naranja y el ambar tambien, un aviso de error y uno de espera
 * se verian igual — y la persona lleva toda la app aprendiendo que no lo son.
 *
 * Los fondos y los textos tampoco cambian, por lo mismo que no cambian entre
 * pantallas: son el lienzo, no la marca.
 *
 * ## Por que una lista cerrada y no un selector de color
 *
 * Porque este proyecto **mide** el contraste en vez de elegirlo a ojo: los
 * acentos claros se rederivaron midiendo, `ContrasteBurbujaTest` exige 4.5:1,
 * y dos neutros de la paleta se usan solo como borde porque no llegan.
 *
 * Un selector RGB rompe esa garantia **en silencio**. Alguien elige un
 * amarillo palido, el texto de su propia burbuja deja de leerse, y ningun test
 * lo detecta porque el color lo puso el usuario. Con una lista cerrada, cada
 * entrada pasa por el mismo test que todo lo demas.
 *
 * ## Los numeros
 *
 * Los cuatro contrastes de cada paleta estan **calculados**, no estimados, y
 * el peor de los veinticuatro es 5.75:1 sobre un minimo exigido de 4.5. La
 * prueba los recalcula: si alguien agrega una paleta a ojo, falla.
 */
enum class Paleta(
    val etiqueta: String,
    /** Acento en tema oscuro, y la tinta que va encima. */
    internal val acentoOsc: Color,
    internal val sobreOsc: Color,
    /** Acento en tema claro, y la tinta que va encima. */
    internal val acentoCla: Color,
    internal val sobreCla: Color,
) {
    // Los contrastes en el comentario son: acento/superficie · tinta/acento,
    // primero oscuro y despues claro.
    /** El de siempre. 13.36 · 14.62 | 6.06 · 6.06 */
    CIAN("Cian", Color(0xFF6CF8F6), Color(0xFF0E1313), Color(0xFF0B6E6D), Color(0xFFFFFFFF)),

    /** 9.16 · 10.03 | 5.75 · 5.75 */
    AZUL("Azul", Color(0xFF7FC4FF), Color(0xFF0E1313), Color(0xFF1565C0), Color(0xFFFFFFFF)),

    /** 8.95 · 9.79 | 7.34 · 7.34 */
    VIOLETA("Violeta", Color(0xFFC9AEFF), Color(0xFF0E1313), Color(0xFF6A3AB2), Color(0xFFFFFFFF)),

    /** 10.89 · 11.92 | 6.28 · 6.28 */
    VERDE("Verde", Color(0xFF7FE39B), Color(0xFF0E1313), Color(0xFF1B6E3C), Color(0xFFFFFFFF)),

    /** 9.50 · 10.40 | 6.33 · 6.33 */
    NARANJA("Naranja", Color(0xFFFFB067), Color(0xFF0E1313), Color(0xFF8A5300), Color(0xFFFFFFFF)),

    /** 8.91 · 9.76 | 7.06 · 7.06 */
    ROSA("Rosa", Color(0xFFFF9EC4), Color(0xFF0E1313), Color(0xFFA32362), Color(0xFFFFFFFF));

    /** Como se ve en el selector, sin depender del tema que este puesto. */
    fun muestra(esClaro: Boolean): Color = if (esClaro) acentoCla else acentoOsc

    /** La tinta que se lee encima de [muestra]. */
    fun tinta(esClaro: Boolean): Color = if (esClaro) sobreCla else sobreOsc
}

/**
 * La paleta que se esta pintando. La fija [WtfuckTheme], igual que [claro].
 *
 * Por que una variable global y no un `CompositionLocal`: porque es lo que ya
 * hacia `claro`, y las 58 pantallas leen los tokens como valores sueltos. Meter
 * un local obligaria a tocarlas todas para ganar exactamente nada.
 */
internal var paleta by mutableStateOf(Paleta.CIAN)

// ---------------------------------------------------------------
//  Los nombres que usa toda la app
// ---------------------------------------------------------------

/**
 * El acento. Se sigue llamando `Cian` a proposito.
 *
 * Renombrarlo a `Acento` habria tocado 58 archivos y cientos de lineas para
 * no cambiar ni un pixel — un diff enorme donde no se veria el cambio real.
 * El nombre miente un poco cuando la paleta es rosa; el KDoc no.
 */
val Cian: Color get() = if (claro) paleta.acentoCla else paleta.acentoOsc
val Ambar: Color get() = if (claro) AmbarCla else AmbarOsc
val Coral: Color get() = if (claro) CoralCla else CoralOsc
val Slate: Color get() = if (claro) SlateCla else SlateOsc
val Taupe: Color get() = if (claro) TaupeCla else TaupeOsc

val BgBase: Color get() = if (claro) BgBaseCla else BgBaseOsc
val BgSurface: Color get() = if (claro) BgSurfaceCla else BgSurfaceOsc
val BgElev: Color get() = if (claro) BgElevCla else BgElevOsc

val TextoPrimario: Color get() = if (claro) TextoPrimarioCla else TextoPrimarioOsc
val TextoSecundario: Color get() = if (claro) TextoSecundarioCla else TextoSecundarioOsc
val TextoTerciario: Color get() = if (claro) TextoTerciarioCla else TextoTerciarioOsc
/**
 * La tinta que va ENCIMA del acento.
 *
 * Sale de la paleta y no es fija: cada acento pide la suya. Un acento oscuro
 * -los del tema claro- pide tinta blanca; uno luminoso -los del oscuro- pide
 * tinta negra. Un solo valor para todos dejaria ilegible la mitad.
 */
val TextoSobreAcento: Color get() = if (claro) paleta.sobreCla else paleta.sobreOsc

// ---------------------------------------------------------------
//  Semantica de estado — el usuario aprende un color por significado
// ---------------------------------------------------------------

/** Entregado / conectado / en linea. */
val EstadoOk: Color get() = Cian

/** En cola, esperando red. Es el estado visible de `msg off`. */
val EstadoPendiente: Color get() = Ambar

/** Fallo de envio, clave cambiada, dispositivo no verificado. */
val EstadoFallo: Color get() = Coral

/** Inactivo, sin conexion, silenciado. */
val EstadoInerte: Color get() = Slate
