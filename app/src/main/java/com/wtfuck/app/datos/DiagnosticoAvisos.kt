package com.wtfuck.app.datos

/**
 * Por que podrian no llegar los avisos con la app cerrada, en orden.
 *
 * ## Por que existe
 *
 * "No me llegan las notificaciones con el telefono suspendido" tiene media
 * docena de causas que se ven igual desde fuera: el permiso apagado, un
 * servidor sin push, un telefono sin servicios de Google, el token sin
 * registrar, el ahorro de bateria... y en Honor, Huawei, Xiaomi y compania, un
 * gestor propio que impide que el aviso despierte la app aunque todo lo demas
 * este bien. Sin esto, la persona prueba cosas a ciegas.
 *
 * Es una funcion pura a proposito: el estado lo junta la pantalla -que es la
 * que puede hablar con Android- y la regla se prueba sin framework.
 */
enum class ProblemaAviso {
    /** Android no deja publicar nada. Sin esto lo demas no importa. */
    SIN_PERMISO,
    /** El servidor no tiene push: con la app cerrada no hay quien la despierte. */
    SERVIDOR_SIN_PUSH,
    /** El telefono no tiene servicios de Google, y el push va por ahi. */
    SIN_GOOGLE,
    /** Todo esta, pero este telefono todavia no le dio su token al servidor. */
    SIN_TOKEN,
    /** La optimizacion de bateria puede retrasar o cortar el aviso. */
    BATERIA_RESTRINGIDA,
    /**
     * Un fabricante con gestor propio. No se puede detectar como esta
     * configurado -no hay API-, asi que en esas marcas se da siempre el
     * consejo, con los pasos de esa marca.
     */
    FABRICANTE_AGRESIVO,
}

data class EstadoAvisos(
    val notificacionesPermitidas: Boolean,
    val servidorConPush: Boolean,
    val googlePlay: Boolean,
    val tokenRegistrado: Boolean,
    val bateriaSinRestriccion: Boolean,
    val fabricante: String,
)

fun problemasDeAvisos(e: EstadoAvisos): List<ProblemaAviso> = buildList {
    if (!e.notificacionesPermitidas) add(ProblemaAviso.SIN_PERMISO)
    if (!e.servidorConPush) {
        add(ProblemaAviso.SERVIDOR_SIN_PUSH)
    } else if (!e.googlePlay) {
        add(ProblemaAviso.SIN_GOOGLE)
    } else if (!e.tokenRegistrado) {
        // Solo con Google presente: sin Google no hay token que registrar, y
        // decir las dos cosas confundiria la causa con su consecuencia.
        add(ProblemaAviso.SIN_TOKEN)
    }
    if (!e.bateriaSinRestriccion) add(ProblemaAviso.BATERIA_RESTRINGIDA)
    if (pasosDelFabricante(e.fabricante) != null) add(ProblemaAviso.FABRICANTE_AGRESIVO)
}

/**
 * Los pasos a mano para las marcas que matan apps en segundo plano, o null.
 *
 * Son los nombres de menu de cada capa: cambian entre versiones, por eso se
 * escriben como ruta aproximada y no como promesa. La de Honor y Huawei va
 * primero porque es la mas comun aqui y la que mas aprieta: con "Gestionar
 * automaticamente", el push no puede despertar una app cerrada.
 */
fun pasosDelFabricante(fabricante: String): String? = when (fabricante.trim().lowercase()) {
    "honor", "huawei" ->
        "Ajustes > Batería > Inicio de aplicaciones > wtfuck: apaga \"Gestionar " +
            "automáticamente\" y deja encendidos Inicio automático, Inicio secundario y " +
            "Ejecutar en segundo plano."
    "xiaomi", "redmi", "poco" ->
        "Ajustes > Aplicaciones > wtfuck: activa Inicio automático, y en Ahorro de " +
            "batería elige \"Sin restricciones\"."
    "samsung" ->
        "Ajustes > Aplicaciones > wtfuck > Batería: elige \"Sin restricciones\", y " +
            "comprueba que wtfuck no esté en \"Aplicaciones en suspensión\"."
    "oppo", "realme", "oneplus" ->
        "Ajustes > Aplicaciones > wtfuck > Uso de batería: permite la actividad en " +
            "segundo plano y el inicio automático."
    "vivo", "iqoo" ->
        "Ajustes > Batería > Consumo en segundo plano > wtfuck: permite el consumo " +
            "alto en segundo plano, y activa el inicio automático."
    "tecno", "infinix", "itel" ->
        "Ajustes > Aplicaciones > wtfuck: permite el inicio automático y quita la " +
            "restricción de batería."
    else -> null
}
