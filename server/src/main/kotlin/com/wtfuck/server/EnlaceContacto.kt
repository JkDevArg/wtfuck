package com.wtfuck.server

import com.wtfuck.protocol.UsuarioPublico
import java.security.SecureRandom
import java.sql.Connection
import java.util.Base64
import java.util.UUID

/**
 * El enlace de contacto: "agregame", para compartir o mostrar como QR.
 *
 * ## Por que un codigo al azar y no el @usuario
 *
 * Es el modelo de los enlaces de usuario de Signal. El usuario es publico y no
 * cambia; un enlace que lo llevara dentro valdria para siempre, y un QR que
 * circula -una captura en un grupo, una foto de un cartel- no se puede
 * recoger. Con un codigo, "restablecer" lo invalida en el acto y la cuenta
 * sigue siendo la misma.
 *
 * ## Que deja hacer, y que no
 *
 * Tenerlo deja ENCONTRAR a la cuenta aunque su "quien me encuentra" diga
 * "nadie": la persona lo repartio, y repartirlo es justamente decir "a ti si".
 * Lo que NO salta es "quien me escribe": si quien lo usa no es conocido, la
 * conversacion nace como SOLICITUD -aunque la cuenta no acepte solicitudes de
 * desconocidos- y la duena decide. Un enlace filtrado puede traer mensajes,
 * no conversaciones abiertas a la fuerza.
 *
 * Un bloqueo, en cualquier sentido, gana siempre: el enlace responde lo mismo
 * que uno que no existe.
 */
object EnlaceContacto {

    private val azar = SecureRandom()

    /** 16 bytes al azar en base64url: 22 caracteres. Adivinarlo no es un plan. */
    fun nuevoToken(): String {
        val b = ByteArray(16)
        azar.nextBytes(b)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    private val FORMA = Regex("^[A-Za-z0-9_-]{22}$")

    fun formaValida(token: String): Boolean = FORMA.matches(token)

    private const val NO_EXISTE = "Ese enlace no existe o ya no funciona."

    fun actual(yo: Auth): String? = Db.query { c ->
        c.prepareStatement("SELECT token FROM enlace_contacto WHERE usuario_id = ?").use { st ->
            st.setObject(1, yo.usuarioId)
            st.executeQuery().use { rs -> rs.primero { it.getString(1) } }
        }
    }

    /** Crea el enlace, o lo cambia por uno nuevo. El anterior deja de servir. */
    fun crear(yo: Auth): String = Db.tx { c ->
        c.prepareStatement(
            """INSERT INTO enlace_contacto (usuario_id, token) VALUES (?, ?)
               ON CONFLICT (usuario_id) DO UPDATE SET token = EXCLUDED.token, creado_en = now()
               RETURNING token"""
        ).use { st ->
            st.setObject(1, yo.usuarioId)
            st.setString(2, nuevoToken())
            st.executeQuery().use { it.next(); it.getString(1) }
        }
    }

    fun borrar(yo: Auth) {
        Db.tx { c ->
            c.prepareStatement("DELETE FROM enlace_contacto WHERE usuario_id = ?").use { st ->
                st.setObject(1, yo.usuarioId)
                st.executeUpdate()
            }
        }
    }

    /** De quien es el enlace, o null. */
    fun duenoDe(c: Connection, token: String): UUID? {
        if (!formaValida(token)) return null
        return c.prepareStatement(
            """SELECT e.usuario_id FROM enlace_contacto e
               JOIN usuario u ON u.id = e.usuario_id
               WHERE e.token = ? AND u.desactivado_en IS NULL"""
        ).use { st ->
            st.setString(1, token)
            st.executeQuery().use { rs -> rs.primero { it.getObject(1, UUID::class.java) } }
        }
    }

    /**
     * A quien lleva el enlace, visto por quien pregunta y con la privacidad
     * de siempre: el nombre o la foto que esa cuenta no le muestra a un
     * desconocido tampoco salen por aqui.
     */
    fun resolver(yo: Auth, token: String): UsuarioPublico = Db.query { c ->
        val dueno = duenoDe(c, token) ?: throw ErrorNegocio(404, NO_EXISTE)
        if (dueno != yo.usuarioId && Autz.hayBloqueo(c, yo.usuarioId, dueno)) {
            throw ErrorNegocio(404, NO_EXISTE)
        }
        Repo.publicoPorId(c, dueno, yo.usuarioId) ?: throw ErrorNegocio(404, NO_EXISTE)
    }

    /**
     * La pagina que se abre si el enlace se toca fuera de la app.
     *
     * No dice de QUIEN es: no se consulta la base. Una vista previa de enlace
     * -la que arman otras mensajerias al pegarlo- o un buscador que la
     * rastree no deben poder averiguar a que cuenta lleva. Y por lo mismo no
     * distingue un codigo vigente de uno revocado: solo si tiene la forma.
     *
     * Sin JavaScript: el boton es un enlace `intent://` que Android resuelve
     * a la app si esta instalada.
     */
    fun pagina(token: String, descarga: String?): String {
        val valido = formaValida(token)
        val abrir = "intent://c/$token#Intent;scheme=wtfuck;package=com.wtfuck.app;end"
        val bajar = descarga?.takeIf { it.startsWith("https://") }
            ?.let { """<p class="chico">¿No tienes wtfuck? <a href="${escapar(it)}">Descárgalo aquí</a>.</p>""" }
            .orEmpty()
        val cuerpo = if (valido) {
            """<h1>Te pasaron un contacto de wtfuck</h1>
               <p>Ábrelo en la app para escribirle.</p>
               <p><a class="boton" href="$abrir">Abrir en wtfuck</a></p>
               $bajar"""
        } else {
            """<h1>Este enlace no es válido</h1>
               <p>Pídele a quien te lo pasó que te lo mande otra vez.</p>
               $bajar"""
        }
        return """<!doctype html>
<html lang="es"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<title>wtfuck</title>
<style>
body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
background:#07090a;color:#e8eeee;font-family:system-ui,sans-serif;padding:16px;box-sizing:border-box}
main{max-width:420px;text-align:center}
h1{font-size:1.4rem;margin:0 0 12px}
p{color:#9fb0b0;line-height:1.5}
.boton{display:inline-block;margin-top:8px;padding:14px 22px;border-radius:14px;
background:#5ef0f0;color:#07090a;font-weight:600;text-decoration:none}
.chico{font-size:.85rem;margin-top:24px}
a{color:#5ef0f0}
</style></head>
<body><main>$cuerpo</main></body></html>"""
    }

    private fun escapar(s: String) =
        s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
}
