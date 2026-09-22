package com.wtfuck.server

import com.wtfuck.protocol.*
import java.sql.Connection
import java.util.UUID

/**
 * Modulo P · Tipos de cuenta y ficha de empresa.
 *
 * Tres tipos excluyentes —`normal`, `desarrollador`, `empresa`— en una sola
 * columna, y una ficha publica aparte para las empresas. El porque de cada
 * decision de esquema esta en la migracion V31; aqui va el porque de las
 * **autorizaciones**, que es lo que de verdad distingue a este modulo.
 *
 * ## Tres puertas distintas, no una
 *
 * Es tentador resolver esto con un solo "¿puede cambiar su tipo?" y ya. No
 * alcanza, porque las tres operaciones responden a preguntas distintas:
 *
 *  1. **Elegir tu tipo** (`normal` ⇄ `empresa`): es una declaracion sobre uno
 *     mismo, como el estado. La decide la propia persona.
 *  2. **Otorgar `desarrollador`**: lo da staff. Un modo con capacidades
 *     tecnicas que cualquiera pudiera activarse es una escalada de privilegio
 *     con otro nombre, aunque hoy solo encienda pantallas de diagnostico.
 *  3. **Verificar una empresa**: lo da staff y solo staff. Cualquiera puede
 *     escribir el nombre de un banco en su ficha; lo que no puede es ponerse el
 *     distintivo que dice que alguien lo comprobo. Sin esa separacion, la ficha
 *     de empresa seria una herramienta de suplantacion —y de las buenas, porque
 *     vendria con la credibilidad de la plataforma detras—.
 *
 * ## La puerta de la beta
 *
 * Mientras el modulo esta en pruebas solo lo ven las cuentas listadas en
 * `WTFUCK_CUENTAS_BETA` (por defecto, la del propietario). Y se cierra **en el
 * servidor**: las rutas responden 404 a quien no esta en la lista.
 *
 * Esconder el boton y dejar la ruta abierta es exactamente el error que el §16
 * del brief prohibe —"nunca confiar unicamente en permisos enviados por el
 * cliente"—, y ademas no funcionaria: la ruta se descubre leyendo el APK.
 *
 * 404 y no 403 por la misma razon de siempre: un 403 confirma que la
 * funcionalidad existe, y en una beta eso ya es informacion.
 */
object Cuentas {

    /**
     * Quien ve el modulo. Se lee UNA vez al arrancar, como el resto de la
     * configuracion: una lista que cambia sola entre peticiones haria que dos
     * llamadas seguidas contestaran cosas distintas sin que nadie tocara nada.
     */
    private val beta: Set<String> by lazy {
        val crudo = System.getenv("WTFUCK_CUENTAS_BETA")
            ?: System.getenv("WTFUCK_PROPIETARIO")
            ?: ""
        crudo.split(',', ' ')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    fun enBeta(username: String): Boolean = username.lowercase() in beta

    private fun exigirBeta(yo: Auth) {
        if (!enBeta(yo.username)) throw ErrorNegocio(404, "No se encontro.")
    }

    // ------------------------------------------------------------------
    //  Leer
    // ------------------------------------------------------------------

    /**
     * Lo que esta cuenta es y puede.
     *
     * Esta ruta **no** exige beta: responde para todo el mundo, y a quien no
     * esta en la beta le dice `puedeElegirTipo = false`. Cerrarla tambien
     * obligaria al cliente a distinguir "no puedo" de "fallo la red", que son
     * dos cosas y se dibujan distinto.
     */
    fun mias(yo: Auth): CapacidadesCuenta = Db.query { c ->
        val tipo = tipoDe(c, yo.usuarioId)
        CapacidadesCuenta(
            tipo = tipo,
            puedeElegirTipo = enBeta(yo.username),
            esDesarrollador = tipo == TipoCuenta.DESARROLLADOR,
            empresa = if (tipo == TipoCuenta.EMPRESA) fichaDe(c, yo.usuarioId) else null,
        )
    }

    fun tipoDe(c: Connection, usuarioId: UUID): String =
        c.prepareStatement("SELECT tipo_cuenta FROM usuario WHERE id = ?").use { st ->
            st.setObject(1, usuarioId)
            st.executeQuery().use { rs -> rs.primero { it.getString(1) } }
        } ?: TipoCuenta.NORMAL

    /** La ficha publica de una empresa, o null si no tiene. */
    fun fichaDe(c: Connection, usuarioId: UUID): FichaEmpresa? =
        c.prepareStatement(
            """SELECT nombre_comercial, categoria, coalesce(descripcion,''),
                      coalesce(sitio_web,''), coalesce(tamano,''), coalesce(ubicacion,''),
                      coalesce(fundada_en, 0), verificada_en IS NOT NULL
               FROM perfil_empresa WHERE usuario_id = ?"""
        ).use { st ->
            st.setObject(1, usuarioId)
            st.executeQuery().use { rs ->
                rs.primero {
                    FichaEmpresa(
                        nombreComercial = it.getString(1),
                        categoria = it.getString(2),
                        descripcion = it.getString(3),
                        sitioWeb = it.getString(4),
                        tamano = it.getString(5),
                        ubicacion = it.getString(6),
                        fundadaEn = it.getInt(7),
                        verificada = it.getBoolean(8),
                    )
                }
            }
        }

    // ------------------------------------------------------------------
    //  Elegir el tipo propio
    // ------------------------------------------------------------------

    /**
     * Cambiar el tipo de la cuenta propia. Solo `normal` y `empresa`.
     *
     * `desarrollador` NO esta en la lista, y el rechazo es explicito en vez de
     * un 400 generico: quien lo intenta merece saber que existe y que no se
     * pide por aqui.
     */
    fun elegirTipo(yo: Auth, req: CambiarTipoReq): CapacidadesCuenta {
        exigirBeta(yo)
        if (req.tipo == TipoCuenta.DESARROLLADOR) {
            throw ErrorNegocio(403, "El modo desarrollador lo asigna el equipo, no se pide.")
        }
        if (req.tipo !in TipoCuenta.AUTOSERVICIO) {
            throw ErrorNegocio(400, "Ese tipo de cuenta no existe.")
        }

        Db.tx { c ->
            Autz.exigirNoSuspendido(c, yo.usuarioId)
            val antes = tipoDe(c, yo.usuarioId)
            if (antes == TipoCuenta.DESARROLLADOR) {
                // Quien es desarrollador no se quita el modo solo. Si pudiera,
                // el registro de quien lo tiene dejaria de ser fiable: bastaria
                // apagarlo un momento para que una revision no lo viera.
                throw ErrorNegocio(403, "El modo desarrollador lo quita el equipo.")
            }

            c.prepareStatement("UPDATE usuario SET tipo_cuenta = ? WHERE id = ?").use { st ->
                st.setString(1, req.tipo); st.setObject(2, yo.usuarioId)
                st.executeUpdate()
            }

            // Dejar de ser empresa se lleva la ficha. Una ficha huerfana
            // seguiria siendo visible desde cualquier consulta que la lea por
            // usuario_id sin mirar el tipo, y esa consulta se escribe sola.
            if (req.tipo != TipoCuenta.EMPRESA) {
                c.prepareStatement("DELETE FROM perfil_empresa WHERE usuario_id = ?").use { st ->
                    st.setObject(1, yo.usuarioId); st.executeUpdate()
                }
            }

            Autz.auditar(
                c, yo.usuarioId, "cuenta.tipo", "usuario", yo.usuarioId,
                objetivoId = yo.usuarioId,
                detalle = Autz.detalleDe("de" to antes, "a" to req.tipo),
            )
        }
        return mias(yo)
    }

    // ------------------------------------------------------------------
    //  La ficha de empresa
    // ------------------------------------------------------------------

    /**
     * Guarda la ficha. Solo si la cuenta ya es de tipo empresa.
     *
     * El orden importa: primero se declara el tipo, despues se llena la ficha.
     * Al reves, guardar la ficha convertiria la cuenta en empresa como efecto
     * secundario, y un cambio de tipo escondido dentro de "guardar datos" es la
     * clase de cosa que nadie audita.
     *
     * **Editar la ficha borra la verificacion.** Si no, bastaria verificarse
     * con datos limpios y cambiar el nombre despues: el distintivo acreditaria
     * unos datos que ya no son los que hay.
     */
    fun guardarFicha(yo: Auth, req: FichaEmpresaReq): FichaEmpresa {
        exigirBeta(yo)

        // Se limpia ANTES de medir y de guardar. La ficha es lo unico de esta
        // plataforma que una cuenta escribe y otra gente lee como si fuera un
        // dato de la plataforma, asi que es el sitio donde un nombre dado
        // vuelta se convierte en suplantacion. Ver `etiquetaLimpia`.
        val nombre = etiquetaLimpia(req.nombreComercial)
        if (nombre.isBlank()) throw ErrorNegocio(400, "La empresa necesita un nombre.")
        if (nombre.length > TopesEmpresa.NOMBRE) {
            throw ErrorNegocio(400, "El nombre es demasiado largo.")
        }
        if (req.categoria !in CategoriaEmpresa.TODAS) {
            throw ErrorNegocio(400, "Esa categoria no existe.")
        }
        if (req.tamano.isNotBlank() && req.tamano !in TamanoEmpresa.TODOS) {
            throw ErrorNegocio(400, "Ese tamano no existe.")
        }
        val anio = req.fundadaEn
        if (anio != 0 && (anio < TopesEmpresa.ANIO_MIN || anio > anioActual() + 1)) {
            throw ErrorNegocio(400, "Ese ano de fundacion no es creible.")
        }
        val sitio = req.sitioWeb.trim()
        if (sitio.isNotBlank() && !sitioValido(sitio)) {
            // Se valida el esquema porque este texto acaba siendo un enlace en
            // el perfil de alguien. Un `javascript:` ahi es una trampa, no una
            // pagina web.
            throw ErrorNegocio(400, "El sitio web tiene que empezar por https://")
        }

        return Db.tx { c ->
            // La ficha es el perfil publico de una empresa: es lo ultimo que
            // deberia poder retocar quien esta cumpliendo una sancion.
            Autz.exigirNoSuspendido(c, yo.usuarioId)
            if (tipoDe(c, yo.usuarioId) != TipoCuenta.EMPRESA) {
                throw ErrorNegocio(409, "Primero cambia la cuenta a tipo empresa.")
            }

            c.prepareStatement(
                """INSERT INTO perfil_empresa
                       (usuario_id, nombre_comercial, categoria, descripcion, sitio_web,
                        tamano, ubicacion, fundada_en, actualizada_en)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())
                   ON CONFLICT (usuario_id) DO UPDATE SET
                       nombre_comercial = excluded.nombre_comercial,
                       categoria        = excluded.categoria,
                       descripcion      = excluded.descripcion,
                       sitio_web        = excluded.sitio_web,
                       tamano           = excluded.tamano,
                       ubicacion        = excluded.ubicacion,
                       fundada_en       = excluded.fundada_en,
                       actualizada_en   = now(),
                       -- Ver la nota: editar invalida la verificacion.
                       verificada_en    = NULL,
                       verificada_por   = NULL"""
            ).use { st ->
                st.setObject(1, yo.usuarioId)
                st.setString(2, nombre)
                st.setString(3, req.categoria)
                // La descripcion conserva sus saltos de linea -es un parrafo, no
                // una etiqueta- pero pierde igual los controles de direccion.
                st.setString(
                    4,
                    req.descripcion
                        .filterNot { it in CONTROLES_DE_DIRECCION }
                        .trim().take(TopesEmpresa.DESCRIPCION).ifBlank { null },
                )
                st.setString(5, sitio.take(TopesEmpresa.SITIO).ifBlank { null })
                st.setString(6, req.tamano.ifBlank { null })
                st.setString(
                    7,
                    etiquetaLimpia(req.ubicacion).take(TopesEmpresa.UBICACION).ifBlank { null },
                )
                if (anio == 0) st.setNull(8, java.sql.Types.SMALLINT) else st.setInt(8, anio)
                st.executeUpdate()
            }

            Autz.auditar(
                c, yo.usuarioId, "empresa.guardar", "usuario", yo.usuarioId,
                objetivoId = yo.usuarioId,
            )
            fichaDe(c, yo.usuarioId) ?: FichaEmpresa()
        }
    }

    /**
     * Un sitio web es https. Ni `javascript:`, ni `data:`, ni http a secas.
     *
     * Tambien se rechazan los espacios y los controles de direccion: aunque
     * hoy el sitio se dibuja como texto y no como enlace tocable, un
     * `https://banco.example@evil.example` o una URL dada vuelta enganan al
     * leerla, que es justo lo que se hace con ella.
     */
    private fun sitioValido(url: String): Boolean =
        url.startsWith("https://", ignoreCase = true) &&
            url.length > "https://".length &&
            url.none { it.isWhitespace() || it in CONTROLES_DE_DIRECCION }

    private fun anioActual(): Int =
        java.time.LocalDate.now(java.time.ZoneOffset.UTC).year

    // ------------------------------------------------------------------
    //  Lo que hace staff
    // ------------------------------------------------------------------

    /**
     * Staff asigna cualquiera de los tres tipos a cualquiera.
     *
     * Aqui SI entra `desarrollador`, y este es el unico camino por el que
     * entra. Exige administrador (80) y no moderador (50): repartir modos con
     * capacidades tecnicas esta del lado de las cosas que no se deshacen del
     * todo, como suspender una cuenta.
     */
    fun asignarTipo(yo: Auth, req: AsignarTipoReq): String = Db.tx { c ->
        Moderacion.exigirStaff(c, yo.usuarioId, Moderacion.ADMINISTRADOR)
        if (req.tipo !in TipoCuenta.TODOS) throw ErrorNegocio(400, "Ese tipo de cuenta no existe.")

        val objetivo = Moderacion.idDeUsername(c, req.username)

        // Ni a uno mismo, ni hacia arriba. Es la misma regla que para
        // sancionar (ver `Moderacion.exigirPorEncima`) y por el mismo motivo:
        // sin ella, la frase "lo otorga staff, nunca uno mismo" se cumple en
        // la ruta de autoservicio y se salta por la puerta de al lado, y dos
        // administradores pueden repartirse modos mutuamente sin que el
        // registro sirva para revisar nada.
        if (objetivo == yo.usuarioId) {
            throw ErrorNegocio(403, "El tipo de cuenta propio no se asigna desde el panel.")
        }
        if (Moderacion.nivel(c, objetivo) >= Moderacion.nivel(c, yo.usuarioId)) {
            throw ErrorNegocio(403, "No puedes cambiar la cuenta de alguien de tu nivel o superior.")
        }

        c.prepareStatement("UPDATE usuario SET tipo_cuenta = ? WHERE id = ?").use { st ->
            st.setString(1, req.tipo); st.setObject(2, objetivo)
            st.executeUpdate()
        }
        if (req.tipo != TipoCuenta.EMPRESA) {
            c.prepareStatement("DELETE FROM perfil_empresa WHERE usuario_id = ?").use { st ->
                st.setObject(1, objetivo); st.executeUpdate()
            }
        }
        Autz.auditar(
            c, yo.usuarioId, "cuenta.asignar_tipo", "usuario", objetivo,
            objetivoId = objetivo,
            detalle = Autz.detalleDe("tipo" to req.tipo),
        )
        req.tipo
    }

    /**
     * Pone o quita el distintivo de verificada.
     *
     * Lo mas delicado del modulo: es la plataforma diciendo "comprobamos que
     * esta cuenta es quien dice ser". Por eso exige administrador y queda en la
     * bitacora con quien lo firmo —sin eso, un distintivo puesto por error no
     * tendria a quien preguntarle—.
     */
    fun verificarEmpresa(yo: Auth, username: String, verificada: Boolean?): Boolean = Db.tx { c ->
        // Staff PRIMERO, y despues la validacion del parametro. Al reves, quien
        // no es staff distinguiria "falta valor" (400) de "no existe" (404), y
        // esa diferencia ya confirma que la ruta existe.
        Moderacion.exigirStaff(c, yo.usuarioId, Moderacion.ADMINISTRADOR)

        // Sin `valor`, o con algo que no es un booleano estricto, se RECHAZA en
        // vez de asumir. Antes caia en un `?: true`: pedir `?valor=0` para
        // retirar un distintivo lo volvia a poner, y la bitacora registraba una
        // verificacion que nadie quiso hacer. En la operacion que afirma una
        // identidad, la ausencia de instruccion no puede significar "si".
        val valor = verificada
            ?: throw ErrorNegocio(400, "Falta valor=true o valor=false.")

        val objetivo = Moderacion.idDeUsername(c, username)

        // **La verificacion la firma otra persona.** Es lo unico que la
        // distingue de una declaracion: si el mismo administrador que escribe
        // "Banco Nacional" en su ficha puede ponerse el distintivo que dice
        // que alguien lo comprobo, el distintivo no acredita nada y la
        // plataforma estaria prestando su credibilidad a una autoafirmacion.
        if (objetivo == yo.usuarioId) {
            throw ErrorNegocio(403, "La verificacion de tu propia ficha la firma otra persona.")
        }

        val filas = if (valor) {
            c.prepareStatement(
                "UPDATE perfil_empresa SET verificada_en = now(), verificada_por = ? WHERE usuario_id = ?"
            ).use { st ->
                st.setObject(1, yo.usuarioId); st.setObject(2, objetivo); st.executeUpdate()
            }
        } else {
            c.prepareStatement(
                "UPDATE perfil_empresa SET verificada_en = NULL, verificada_por = NULL WHERE usuario_id = ?"
            ).use { st ->
                st.setObject(1, objetivo); st.executeUpdate()
            }
        }
        if (filas == 0) throw ErrorNegocio(404, "Esa cuenta no tiene ficha de empresa.")

        Autz.auditar(
            c, yo.usuarioId, "empresa.verificar", "usuario", objetivo,
            objetivoId = objetivo,
            detalle = Autz.detalleDe("verificada" to valor),
        )
        valor
    }
}
