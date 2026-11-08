// Recepción: el bot oficial de bienvenida y ayuda (@rootbot).
//
// GUIONADO, sin IA. Todo lo que contesta está en este archivo, con respuestas
// fijas elegidas por intención (palabras clave). Por qué así y no con una IA:
//
//  - wtfuck es E2EE. Un bot oficial con el que hablan TODOS los usuarios nuevos
//    no puede mandar cada mensaje a un proveedor de IA: eso sacaría de la
//    burbuja cifrada el primer contacto de cada persona. Guionado = nada sale.
//  - Es determinista, gratis y no se puede "jailbreakear" para que diga
//    cualquier cosa en nombre de wtfuck.
//
// Qué NO hace: no tiene la sesión del usuario, así que NO crea ni lista los
// bots del usuario por él. Explica y lo manda a la pantalla correcta con un
// deep-link `wtfuck://abrir?p=<ruta>` (ver la lista blanca en la app, Pedidos).

export interface CfgRecepcion {
  /** Dominio de la versión web, si la instancia la publica (ej "webfck.ejemplo.com"). */
  web?: string;
  /** Cómo se llama el bot, para la bienvenida. */
  nombre?: string;
}

/** Un deep-link que abre una pantalla de la app. La ruta debe estar en la lista blanca de la app. */
export const abrir = (ruta: string) => `wtfuck://abrir?p=${ruta}`;

/** Normaliza para comparar: minúsculas, sin tildes, sin signos, espacios simples. */
function normalizar(s: string): string {
  return s
    .toLowerCase()
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '') // tildes
    .replace(/[¿?¡!.,;:()"']/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

interface Tema {
  clave: string;
  titulo: string; // lo que se ve en el menú
  patrones: RegExp; // intención (sobre el texto normalizado)
  texto: (c: CfgRecepcion) => string;
}

/** "la versión web" o "la versión web (https://dominio)" según haya dominio. */
function web(c: CfgRecepcion): string {
  return c.web ? `la versión web (https://${c.web})` : 'la versión web';
}

// El orden importa: gana el PRIMER tema cuyo patrón matchee. Los más específicos
// van antes que los genéricos.
const TEMAS: Tema[] = [
  {
    clave: 'bots',
    titulo: 'Crear un bot',
    patrones: /\b(bot|bots|automatiz|rootbot|crear un bot)/,
    texto: () =>
      [
        'Podés crear hasta 3 bots. Un bot es una cuenta cifrada con un programa detrás; lo corrés vos en tu propio servidor (una VM, EC2, Lambda, tu PC…), con el código que te damos.',
        '',
        'Pasos:',
        '1. Abrí tu Perfil → Mis bots.  👉 ' + abrir('bots'),
        '2. Elegí un nombre y creá el bot: te da un código de un solo uso.',
        '3. En tu servidor, configurás el cliente del bot con ese código y lo prendés.',
        '',
        'El servidor de wtfuck nunca corre ni descifra tu bot: todo lo que hable va cifrado igual que el resto.',
      ].join('\n'),
  },
  {
    clave: 'recuperar',
    titulo: 'Recuperar mi contraseña / cuenta',
    patrones: /\b(recuper|olvid|contrasena|clave|password|perdi la cuenta|no puedo entrar|acceso)/,
    texto: () =>
      [
        'Hay tres formas de recuperar el acceso:',
        '',
        '• Código de recuperación: el que te dieron al crear la cuenta (28 símbolos). Guardalo bien.',
        '• Frase de recuperación: si activaste una frase, recuperás con ella en un teléfono nuevo.  👉 ' + abrir('seguridad'),
        '• Enlace de un administrador: si no tenés ni código ni frase, un admin te genera un enlace de un solo uso para poner una contraseña nueva.',
        '',
        '⚠️ Importante: al recuperar, NO desinstales la app ni uses "Perdí mi teléfono", o se borra el historial de este dispositivo. Tus mensajes viven solo en el teléfono: activá la copia de seguridad.  👉 ' + abrir('copia-seguridad'),
      ].join('\n'),
  },
  {
    clave: 'copia',
    titulo: 'Copia de seguridad (no perder mensajes)',
    patrones: /\b(copia|backup|respaldo|perder mensajes|perdi mensajes|se borraron|no perder)/,
    texto: () =>
      [
        'Tus mensajes viven SOLO en tu teléfono (ni el servidor ni nosotros los vemos). Si cambiás de teléfono, reinstalás o recuperás la cuenta sin una copia, se pierden.',
        '',
        'Activá la copia de seguridad para no perderlos.  👉 ' + abrir('copia-seguridad'),
      ].join('\n'),
  },
  {
    clave: 'privacidad',
    titulo: 'Privacidad y cifrado',
    patrones: /\b(privacidad|cifrad|e2ee|seguro|seguridad|espian|leer mis mensajes|buzon)/,
    texto: (c) =>
      [
        'wtfuck es cifrado de punta a punta: solo vos y con quien hablás pueden leer los mensajes. El servidor es un "buzón tonto": reenvía sobres cerrados, no puede abrirlos.',
        '',
        '• Los mensajes se guardan cifrados en tu teléfono, con una llave que no es tu contraseña.',
        '• Hablar conmigo también va cifrado: soy un bot, pero no mando nada fuera de la burbuja.',
        c.web ? '• En ' + web(c) + ' aplica lo mismo.' : '',
      ]
        .filter(Boolean)
        .join('\n'),
  },
  {
    clave: 'llamadas',
    titulo: 'Llamadas y videollamadas',
    patrones: /\b(llamad|videollamad|video|llamar|audio|voz)/,
    texto: (c) =>
      [
        'Podés hacer llamadas y videollamadas cifradas, desde la app y desde ' + web(c) + '.',
        '',
        'Entrá a un chat y tocá el ícono de llamada (teléfono) o de video arriba. La señalización va cifrada igual que los mensajes.',
      ].join('\n'),
  },
  {
    clave: 'grupos',
    titulo: 'Grupos y canales',
    patrones: /\b(grupo|grupos|canal|canales|comunidad|comunidades)/,
    texto: () =>
      [
        'Grupos: conversación entre varios, todos escriben.',
        'Canales: publicás vos (o los admins) y el resto lee.',
        '',
        '• Podés ponerle foto a un grupo o canal desde su información.',
        '• En un canal podés publicar con tu nombre o con el nombre del canal (lo elegís por publicación).',
        'Creá uno desde el botón + en la lista de chats.',
      ].join('\n'),
  },
  {
    clave: 'invitar',
    titulo: 'Invitar gente',
    patrones: /\b(invit|agregar a alguien|sumar|contacto nuevo|mi enlace|enlace de contacto)/,
    texto: () =>
      [
        'Para que alguien te escriba sin darle tu número, compartí tu enlace de contacto.',
        '👉 ' + abrir('mi-enlace'),
        '',
        'Y para invitar a alguien a crear cuenta (incluso desde la web), generá una invitación.',
        '👉 ' + abrir('invitar'),
      ].join('\n'),
  },
  {
    clave: 'stickers',
    titulo: 'Stickers y GIFs',
    patrones: /\b(sticker|stickers|gif|gifs|figurit|emoji)/,
    texto: (c) =>
      'En ' + web(c) + ' tenés GIFs y stickers: al escribir un mensaje, usá los botones de GIF y sticker. Podés crear tus propios stickers desde una imagen.',
  },
  {
    clave: 'perfil',
    titulo: 'Mi perfil y foto',
    patrones: /\b(perfil|foto|nombre|estado|avatar|mi cuenta)/,
    texto: () => 'Cambiá tu nombre, estado y foto desde tu Perfil (tu foto arriba a la izquierda en la lista de chats).',
  },
  {
    clave: 'web',
    titulo: 'Usar wtfuck en la web',
    patrones: /\b(web|navegador|chrome|computadora|pc|escritorio|vincular)/,
    texto: (c) =>
      c.web
        ? [
            'Podés usar wtfuck en el navegador desde https://' + c.web + '.',
            '',
            'Si ya tenés la app, entrá a "Ya tengo la app" y vinculá el navegador con el código que te da la app. También podés crear una cuenta nueva desde ahí (con invitación).',
          ].join('\n')
        : 'Esta instancia también se puede usar desde el navegador. Pedile el enlace a quien te invitó.',
  },
  {
    clave: 'humano',
    titulo: 'Hablar con una persona',
    patrones: /\b(humano|persona|soporte|ayuda real|hablar con alguien|administrador|admin|reclamo|queja)/,
    texto: () =>
      'Soy un bot automático, no una persona. Para algo que no resuelvo acá, escribile a quien te invitó o a un administrador de tu comunidad.',
  },
];

/** El menú de temas: la lista de lo que el bot sabe explicar, numerada. */
export function menu(): string {
  const lineas = TEMAS.map((t, i) => `  ${i + 1}. ${t.titulo}`);
  return ['Puedo ayudarte con:', ...lineas, '', 'Escribime el número o contame qué necesitás (ej: "cómo creo un bot", "perdí mi contraseña").'].join('\n');
}

/** Bienvenida del primer contacto: quién soy + el menú. */
export function bienvenida(c: CfgRecepcion = {}): string {
  const quien = c.nombre ? `Soy ${c.nombre}, el` : 'Soy el';
  return [`¡Hola! 👋 ${quien} asistente de wtfuck. Te ayudo a arrancar y resuelvo dudas.`, '', menu()].join('\n');
}

/**
 * La respuesta guionada a un mensaje. Función pura: dado el texto (y la config
 * de la instancia), devuelve qué contestar. Sin IA, sin red, sin estado.
 */
export function responder(texto: string, c: CfgRecepcion = {}): string {
  const t = normalizar(texto);

  // Vacío o pedido explícito de ayuda/menú → el menú.
  if (!t || /^(\/)?(ayuda|start|help|menu|temas|hola|buenas|hey|inicio)$/.test(t)) {
    return menu();
  }

  // ¿Eligió un número del menú?
  const n = Number(t);
  if (Number.isInteger(n) && n >= 1 && n <= TEMAS.length) {
    return TEMAS[n - 1]!.texto(c);
  }

  // Intención por palabras clave: gana el primero que matchee.
  for (const tema of TEMAS) {
    if (tema.patrones.test(t)) return tema.texto(c);
  }

  // No entendí: lo digo y muestro el menú (nunca dejo al usuario sin salida).
  return ['No estoy seguro de qué necesitás. 🤔', '', menu()].join('\n');
}

/** Para los tests y el menú de la app: las rutas que el bot enlaza. */
export const RUTAS_ENLAZADAS = ['bots', 'seguridad', 'copia-seguridad', 'mi-enlace', 'invitar'] as const;
