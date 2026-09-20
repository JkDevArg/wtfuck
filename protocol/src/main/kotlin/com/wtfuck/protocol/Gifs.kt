package com.wtfuck.protocol

import kotlinx.serialization.Serializable

/**
 * Buscador de GIFs (modulo D.6).
 *
 * DECISION DE PRIVACIDAD: la app NO habla con Giphy. Todo pasa por nuestro
 * servidor, que actua de intermediario.
 *
 * Si el telefono llamara directo al proveedor, este veria dos cosas que en un
 * mensajero cifrado no deberia ver: que busca cada persona -"renuncia",
 * "hospital", "te extrano"- y la direccion IP de quien lo busca. Y al abrir la
 * vista previa veria tambien la IP de quien RECIBE el mensaje, sin que esa
 * persona haya pedido nada.
 *
 * El costo del intermediario es que nuestro servidor si ve las busquedas. Es
 * un costo real y menor: ese servidor ya sabe que existe un adjunto, y a
 * diferencia del proveedor es nuestro, con nuestras politicas de retencion.
 *
 * Los bytes del GIF tambien se traen por aqui, por lo mismo: pedirlos al CDN
 * del proveedor filtraria la IP igual que la busqueda.
 */

const val RUTA_GIFS = "/v1/gifs"

/**
 * La forma de un id de GIF.
 *
 * Los ids de Giphy son alfanumericos. Esto no es una curiosidad: ese id viaja
 * desde un TERCERO hasta tres sitios donde importa que sea lo que dice ser.
 *
 *  1. El servidor lo **interpola en una URL** hacia api.giphy.com, con la clave
 *     de la API pegada detras.
 *  2. El cliente lo **interpola en un nombre de archivo** para el temporal.
 *  3. Cualquiera autenticado puede pedir `GET /v1/gifs/{id}/bytes` con lo que
 *     se le ocurra, asi que el id tampoco es "lo que dijo Giphy": es entrada
 *     de usuario.
 *
 * Con un `../` dentro, el punto 1 se convierte en recorrido de rutas sobre
 * api.giphy.com llevando nuestra clave, y el punto 2 en escritura fuera del
 * directorio temporal de la app —donde, un poco mas alla, estan sus bases de
 * datos—.
 *
 * Se valida en los tres sitios a proposito. Un id que no tiene esta forma no es
 * un id, y no hace falta ponerse de acuerdo sobre de quien era la culpa.
 */
val FORMA_GIF_ID = Regex("^[A-Za-z0-9]{1,64}$")

/**
 * Lo que se acepta del titulo que manda el buscador.
 *
 * Es texto de un tercero que se dibuja en la rejilla del selector. Mismo
 * razonamiento que los topes del contenido de un sobre: quien lo escribe no es
 * esta app.
 */
const val TOPE_TITULO_GIF = 120

@Serializable
data class GifResumen(
    val id: String,
    val titulo: String = "",
    val ancho: Int = 0,
    val alto: Int = 0,
    /** Tamano aproximado del archivo, para avisar antes de mandarlo. */
    val bytes: Long = 0,
)

@Serializable
data class BusquedaGifs(
    val resultados: List<GifResumen> = emptyList(),
    /**
     * Por que no hay resultados, cuando no los hay por configuracion.
     *
     * Se separa de "no encontre nada": que el proveedor no este configurado no
     * es culpa de la busqueda, y la interfaz tiene que decir cosas distintas.
     */
    val aviso: String = "",
)
