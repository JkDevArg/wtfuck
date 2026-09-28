package com.wtfuck.app.datos

/**
 * La politica de los mensajes temporales: cuando vence uno.
 *
 * ## Por que es un objeto aparte y no dos lineas en el Repositorio
 *
 * Porque es la unica parte de la funcion que puede fallar EN SILENCIO. Que el
 * selector no se dibuje se ve; que un mensaje no se borre, no: el sintoma es
 * que algo sigue ahi meses despues, y nadie mira. Aqui es una funcion pura con
 * pruebas, en vez de una expresion enterrada en una corrutina que solo se
 * comprueba a mano y con dos telefonos.
 *
 * ## El reparto de responsabilidades
 *
 * El servidor no puede hacer cumplir nada: no tiene el mensaje. Entrega el
 * sobre y lo olvida —eso es el buzon tonto—. Asi que el vencimiento lo pone y
 * lo ejecuta CADA cliente sobre su propia copia. Lo que el servidor aporta es
 * un solo valor, el temporizador de la conversacion, y que todos se enteren de
 * los cambios.
 *
 * El limite honesto de esto: borrar en el telefono ajeno depende de que la app
 * del otro lo haga. Contra alguien que captura la pantalla o modifica su
 * cliente, ningun temporizador sirve, y la pantalla lo dice en vez de
 * insinuar lo contrario.
 */
object Temporales {

    /**
     * Cuando vence un mensaje. `0` = nunca.
     *
     * @param segundos el temporizador de la conversacion. `<= 0` = permanentes.
     * @param creadoEn cuando lo escribieron, segun el reloj de QUIEN ESCRIBIO.
     * @param ahora el reloj de este telefono.
     *
     * ## El tope contra un reloj mentiroso
     *
     * `creadoEn` lo elige quien manda. Un emisor con la hora adelantada -por
     * error o a proposito- pondria una fecha futura y su mensaje "de 30
     * segundos" viviria dias en el telefono ajeno. Por eso el resultado se
     * topa con `ahora + plazo`: nadie puede estirar el plazo mas alla de lo
     * que este telefono acepto.
     *
     * Al reves no se corrige. Si `creadoEn` viene del pasado el mensaje vence
     * antes, o al instante — y es deliberado: un mensaje que llega tarde YA es
     * viejo, y equivocarse del lado de borrar es el lado correcto en el que
     * equivocarse.
     */
    fun vencimiento(segundos: Int, creadoEn: Long, ahora: Long): Long {
        if (segundos <= 0) return 0
        // El `toLong()` es obligatorio, no un adorno: 90 dias en milisegundos
        // son 7.776.000.000 y eso desborda un Int. Con la multiplicacion en
        // Int el plazo mas largo daria un numero negativo y el mensaje naceria
        // vencido — o sea, el plazo mas largo se comportaria como el mas corto.
        val plazo = segundos.toLong() * 1000L
        return minOf(creadoEn + plazo, ahora + plazo)
    }
}
