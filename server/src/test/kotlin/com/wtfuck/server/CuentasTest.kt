package com.wtfuck.server

import com.wtfuck.protocol.CapacidadesCuenta
import com.wtfuck.protocol.CategoriaEmpresa
import com.wtfuck.protocol.FichaEmpresa
import com.wtfuck.protocol.FichaEmpresaReq
import com.wtfuck.protocol.TamanoEmpresa
import com.wtfuck.protocol.TipoCuenta
import com.wtfuck.protocol.TopesEmpresa
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Modulo P · Las listas del protocolo, que son la autorizacion escrita.
 *
 * ## Por que esto se prueba sin base de datos
 *
 * `pruebas/cuentas.mjs` ya recorre el modulo entero contra un servidor de
 * verdad: la puerta de la beta, los 404 y 403, el distintivo, la ficha
 * huerfana. Lo que no puede hacer es fallar **antes de arrancar nada**, y hay
 * una decision de este modulo que merece exactamente eso.
 *
 * `TipoCuenta.AUTOSERVICIO` no es una lista de opciones: es la frontera entre
 * lo que uno se declara a si mismo y lo que otorga staff. Anadir
 * `desarrollador` ahi es una linea de diff que se lee inofensiva —una lista con
 * un elemento mas— y es una escalada de privilegio. Una prueba que se cae con
 * esa linea es la unica forma de que se lea como lo que es.
 *
 * El resto de lo que hay aqui es de la misma familia: constantes que otro
 * archivo da por ciertas. La migracion V31 da por cierto que los tres valores
 * se escriben asi; `guardarFicha` da por cierto que la categoria por defecto de
 * una peticion esta en la lista de categorias; la columna `fundada_en` es un
 * `smallint` y da por cierto que el ano minimo cabe. Nada de eso lo comprueba
 * el compilador.
 *
 * ## Lo que NO esta aqui, a proposito
 *
 * La validacion del sitio web (`sitioValido` en `Cuentas.kt`) es privada y se
 * queda privada: hacerla publica para probarla seria ensanchar la superficie
 * del objeto por conveniencia de una prueba. Ya esta cubierta desde fuera en
 * `pruebas/cuentas.mjs`, que manda `javascript:alert(1)` y `http://` por la
 * ruta real y espera 400 en los dos casos —que es ademas donde importa, porque
 * ahi se comprueba que el rechazo llega hasta la respuesta HTTP y no solo hasta
 * un `if`—.
 *
 * Tampoco hay nada que toque la base: eso es integracion y ya existe.
 */
class TiposDeCuentaTest {

    @Test
    fun `desarrollador NO se puede pedir para uno mismo`() {
        // LA invariante del modulo. Si esta prueba se pone roja, alguien
        // convirtio un modo que otorga staff en uno que se activa solo, y la
        // via de autoservicio (`elegirTipo`) lo dejaria pasar sin mas.
        assertFalse(
            "desarrollador en AUTOSERVICIO es una escalada de privilegio",
            TipoCuenta.DESARROLLADOR in TipoCuenta.AUTOSERVICIO,
        )
    }

    @Test
    fun `lo que uno se declara a si mismo es normal o empresa, y nada mas`() {
        // Con `listOf` exacto y no con `contains`: lo que se fija es que no
        // haya un cuarto elemento que nadie miro.
        assertEquals(listOf(TipoCuenta.NORMAL, TipoCuenta.EMPRESA), TipoCuenta.AUTOSERVICIO)
    }

    @Test
    fun `los tipos son tres, y estos tres`() {
        assertEquals(3, TipoCuenta.TODOS.size)
        assertEquals(
            listOf(TipoCuenta.NORMAL, TipoCuenta.DESARROLLADOR, TipoCuenta.EMPRESA),
            TipoCuenta.TODOS,
        )
    }

    @Test
    fun `no hay tipos repetidos`() {
        // Un repetido no rompe nada visible, pero convierte "los tres tipos"
        // en una frase que ya no describe la lista.
        assertEquals(TipoCuenta.TODOS.size, TipoCuenta.TODOS.toSet().size)
    }

    @Test
    fun `todo lo de autoservicio es tambien un tipo valido`() {
        // `elegirTipo` valida contra AUTOSERVICIO y escribe en la columna, que
        // valida contra TODOS. Si AUTOSERVICIO tuviera algo que TODOS no, el
        // servidor aceptaria la peticion y la base la rechazaria despues: un
        // 500 donde deberia haber un 400.
        assertTrue(TipoCuenta.TODOS.containsAll(TipoCuenta.AUTOSERVICIO))
    }

    @Test
    fun `los valores son los literales que acepta el CHECK de la V31`() {
        // La migracion escribe estos tres a mano en el CHECK. Renombrar una
        // constante aqui compila igual y rompe toda escritura en produccion,
        // porque el CHECK no se entera.
        assertEquals("normal", TipoCuenta.NORMAL)
        assertEquals("desarrollador", TipoCuenta.DESARROLLADOR)
        assertEquals("empresa", TipoCuenta.EMPRESA)
    }
}

/**
 * Las categorias, que son una lista cerrada justamente para poder buscarlas.
 */
class CategoriasDeEmpresaTest {

    @Test
    fun `toda categoria de la lista tiene su propio nombre legible`() {
        // El `when` de `legible` termina en `else -> "Otra"`. Anadir una
        // categoria a TODAS y olvidarse de la rama no rompe la compilacion:
        // la categoria simplemente se dibuja como "Otra" en el perfil de
        // alguien, que es un dato mal puesto, no un error.
        for (c in CategoriaEmpresa.TODAS) {
            if (c == CategoriaEmpresa.OTRA) continue
            assertFalse("le falta la rama en legible(): $c", CategoriaEmpresa.legible(c) == "Otra")
            assertTrue("legible() vacio para $c", CategoriaEmpresa.legible(c).isNotBlank())
        }
    }

    @Test
    fun `otra se dibuja Otra, y es la unica que deberia`() {
        assertEquals("Otra", CategoriaEmpresa.legible(CategoriaEmpresa.OTRA))
    }

    @Test
    fun `dos categorias distintas no se dibujan igual`() {
        // Dos etiquetas identicas en el desplegable son dos opciones que no se
        // pueden distinguir al elegir, y un filtro del directorio que devuelve
        // cosas que no parecen del mismo grupo.
        val legibles = CategoriaEmpresa.TODAS.map { CategoriaEmpresa.legible(it) }
        assertEquals(legibles.size, legibles.toSet().size)
    }

    @Test
    fun `una categoria inventada cae en Otra en vez de dibujarse cruda`() {
        // `legible` recibe lo que hay guardado en la fila, y una fila puede
        // venir de una version anterior o de una migracion a medias. Que caiga
        // en "Otra" es lo que impide que el valor crudo acabe en pantalla.
        assertEquals("Otra", CategoriaEmpresa.legible("mineria"))
        assertEquals("Otra", CategoriaEmpresa.legible(""))
        assertEquals("Otra", CategoriaEmpresa.legible("javascript:alert(1)"))
    }

    @Test
    fun `la comparacion es exacta, no laxa`() {
        // Si algun dia `legible` empezara a comparar ignorando mayusculas o
        // tildes, estaria aceptando valores que la validacion de `guardarFicha`
        // rechaza: dos ideas distintas de que es una categoria valida.
        assertEquals("Otra", CategoriaEmpresa.legible("EDUCACION"))
        assertEquals("Otra", CategoriaEmpresa.legible("educación"))
        assertEquals("Otra", CategoriaEmpresa.legible(" educacion"))
    }

    @Test
    fun `los valores guardados van en minusculas ASCII y sin tildes`() {
        // Lo guardado y lo dibujado son dos cosas: "Educación" se lee, pero lo
        // que va a la columna indexada por `empresa_por_categoria` no lleva
        // tildes ni mayusculas, porque si no la misma categoria tendria varias
        // escrituras y el indice dejaria de agrupar.
        for (c in CategoriaEmpresa.TODAS) {
            assertTrue("categoria con forma rara: $c", c.matches(Regex("[a-z]+")))
        }
    }

    @Test
    fun `no hay categorias repetidas`() {
        assertEquals(CategoriaEmpresa.TODAS.size, CategoriaEmpresa.TODAS.toSet().size)
    }

    @Test
    fun `la categoria por defecto de una peticion es una categoria valida`() {
        // `FichaEmpresaReq` trae OTRA por defecto y `guardarFicha` rechaza con
        // 400 lo que no este en TODAS. Si el defecto se saliera de la lista,
        // una peticion que solo mandara el nombre —lo minimo— fallaria sin que
        // quien la manda haya escrito nada mal.
        assertTrue(CategoriaEmpresa.OTRA in CategoriaEmpresa.TODAS)
        assertTrue(FichaEmpresaReq(nombreComercial = "Acme").categoria in CategoriaEmpresa.TODAS)
    }
}

/**
 * Los tamanos, que son rangos y se muestran en ese orden.
 */
class TamanosDeEmpresaTest {

    @Test
    fun `no hay tamanos repetidos`() {
        // Dos veces el mismo rango en el desplegable son dos opciones que hacen
        // lo mismo, y quien elige no tiene forma de saber cual es cual.
        assertEquals(TamanoEmpresa.TODOS.size, TamanoEmpresa.TODOS.toSet().size)
    }

    @Test
    fun `ningun tamano esta en blanco`() {
        // El blanco ya significa otra cosa: `guardarFicha` trata `tamano`
        // vacio como "no lo dijo" y lo guarda como NULL sin validarlo. Un
        // elemento en blanco dentro de la lista seria una opcion elegible que
        // se guarda como ausencia de dato.
        for (t in TamanoEmpresa.TODOS) {
            assertTrue("hay un tamano en blanco en la lista", t.isNotBlank())
        }
    }

    @Test
    fun `los rangos van de menor a mayor`() {
        // El orden de la lista es el orden del desplegable. Desordenado no
        // falla nada; simplemente deja de leerse como una escala.
        val desde = TamanoEmpresa.TODOS.map { it.takeWhile(Char::isDigit).toInt() }
        assertEquals(desde.sorted(), desde)
        assertEquals(desde.size, desde.toSet().size)
    }

    @Test
    fun `empieza en una persona y termina en el rango abierto`() {
        // Los dos extremos son los que no pueden faltar: sin el "1" una persona
        // sola no tiene donde ponerse, y sin el rango abierto el ultimo tramo
        // dejaria fuera a cualquiera que lo pase.
        assertEquals(TamanoEmpresa.UNO, TamanoEmpresa.TODOS.first())
        assertEquals(TamanoEmpresa.CORPORACION, TamanoEmpresa.TODOS.last())
        assertTrue(TamanoEmpresa.CORPORACION.endsWith("+"))
    }
}

/**
 * Los topes de la ficha, que es un perfil publico.
 */
class TopesDeFichaTest {

    @Test
    fun `todos los topes son positivos`() {
        // Un tope en 0 o negativo no recorta: con `take(0)` el campo se guarda
        // vacio siempre, y el nombre con tope 0 haria que ninguna ficha se
        // pudiera guardar nunca.
        assertTrue(TopesEmpresa.NOMBRE > 0)
        assertTrue(TopesEmpresa.DESCRIPCION > 0)
        assertTrue(TopesEmpresa.SITIO > 0)
        assertTrue(TopesEmpresa.UBICACION > 0)
        assertTrue(TopesEmpresa.ANIO_MIN > 0)
    }

    @Test
    fun `los campos de una linea son mas cortos que la descripcion`() {
        // Nombre, sitio y ubicacion se dibujan en una linea; la descripcion es
        // un parrafo. Si un campo de una linea admitiera mas que el parrafo,
        // el tope habria dejado de describir para que sirve el campo.
        assertTrue(TopesEmpresa.NOMBRE < TopesEmpresa.DESCRIPCION)
        assertTrue(TopesEmpresa.SITIO < TopesEmpresa.DESCRIPCION)
        assertTrue(TopesEmpresa.UBICACION < TopesEmpresa.DESCRIPCION)
    }

    @Test
    fun `el tope del sitio da para una URL de verdad`() {
        // El sitio se recorta con `take`, no se rechaza: un tope corto
        // guardaria media URL, que es un enlace roto guardado en silencio.
        assertTrue(TopesEmpresa.SITIO > "https://".length * 4)
    }

    @Test
    fun `el ano minimo es un ano, no un numero cualquiera`() {
        val esteAno = java.time.LocalDate.now(java.time.ZoneOffset.UTC).year
        assertTrue(TopesEmpresa.ANIO_MIN in 1000..esteAno)
    }

    @Test
    fun `el rango de anos cabe en el smallint de la V31`() {
        // `fundada_en` es `smallint`. `guardarFicha` acepta hasta el ano que
        // viene, asi que el maximo se mueve solo cada 1 de enero: si el tope de
        // la columna se pasara, el rechazo llegaria como error de la base
        // —un 500— y no como el 400 que le corresponde.
        val maximoQueAcepta = java.time.LocalDate.now(java.time.ZoneOffset.UTC).year + 1
        assertTrue(TopesEmpresa.ANIO_MIN <= Short.MAX_VALUE)
        assertTrue("el ano maximo aceptable ya no cabe en smallint",
            maximoQueAcepta <= Short.MAX_VALUE)
    }
}

/**
 * Los valores por defecto, que son lo que se ve cuando falta un campo.
 *
 * Importan mas de lo que parece: estas clases se deserializan de JSON, y un
 * campo ausente en el JSON se queda con su defecto. O sea que el defecto es lo
 * que decide el JSON que manda un cliente viejo, uno hecho a mano, o uno al que
 * le falta una version.
 */
class DefectosDeLaFichaTest {

    @Test
    fun `una ficha nace SIN verificar`() {
        // Tener ficha es decir quien decis que sos; estar verificada es que
        // alguien lo comprobo. Si el defecto fuera `true`, una ficha a la que
        // le faltara el campo se dibujaria con el distintivo de la plataforma
        // detras, que es justo la suplantacion que el modulo evita.
        assertFalse(FichaEmpresa().verificada)
    }

    @Test
    fun `unas capacidades por defecto no conceden nada`() {
        // El mismo criterio, del lado del cliente: si la respuesta no se pudo
        // leer entera, lo que queda es una cuenta normal sin permisos, no una
        // con el modulo abierto.
        val c = CapacidadesCuenta()
        assertEquals(TipoCuenta.NORMAL, c.tipo)
        assertFalse(c.puedeElegirTipo)
        assertFalse(c.esDesarrollador)
        assertNull(c.empresa)
    }

    @Test
    fun `una ficha vacia no afirma nada de si misma`() {
        val f = FichaEmpresa()
        assertEquals("", f.nombreComercial)
        assertEquals(CategoriaEmpresa.OTRA, f.categoria)
        assertEquals("", f.sitioWeb)
        // 0 es ausencia de dato, no el ano 0: `guardarFicha` guarda NULL con
        // ese valor en vez de rechazarlo por increible.
        assertEquals(0, f.fundadaEn)
    }
}

/**
 * La puerta de la beta, del lado que se puede mirar sin base de datos.
 *
 * Quien esta DENTRO depende del entorno y eso lo prueba `pruebas/cuentas.mjs`
 * contra un servidor levantado a proposito. Lo que si se puede fijar aqui es el
 * lado que no depende de nada: que la puerta este cerrada por defecto.
 */
class PuertaDeLaBetaTest {

    @Test
    fun `quien no esta en la lista no esta en la beta`() {
        // Cerrada por defecto. Si `enBeta` devolviera true ante la duda —por
        // una lista vacia tratada como "sin restriccion", por ejemplo— el
        // modulo entero quedaria abierto sin que nadie cambiara una linea de
        // autorizacion.
        assertFalse(Cuentas.enBeta("nadie_que_este_configurado_aqui"))
        assertFalse(Cuentas.enBeta(""))
    }
}
