#!/usr/bin/env python3
"""
Genera CHANGELOG.md a partir de las Novedades de la app.

## Por que se genera y no se escribe

La lista de cambios ya existe, en español y escrita para quien usa la app: es
`NovedadesPantalla.kt`, lo que la app muestra en "Novedades". Un CHANGELOG
escrito a mano seria una SEGUNDA copia de lo mismo, y dos copias de un texto
terminan diciendo cosas distintas. Asi hay una sola fuente: se edita la
pantalla de Novedades y este script rehace el archivo.

Si existe `despliegue/notas/<version>.md`, se agrega debajo de esa version como
"Para desplegar": migraciones, variables nuevas, compatibilidad. Eso no es para
quien usa la app y por eso no va en Novedades.

Uso (desde la raiz del repo):

    python despliegue/changelog.py
"""
import io
import os
import re
import sys

RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FUENTE = os.path.join(RAIZ, 'app', 'src', 'main', 'java', 'com', 'wtfuck', 'app', 'ui', 'NovedadesPantalla.kt')
NOTAS = os.path.join(RAIZ, 'despliegue', 'notas')
SALIDA = os.path.join(RAIZ, 'CHANGELOG.md')

# El orden en que se muestran las secciones y como se titulan.
SECCIONES = [
    ('NUEVO', 'Nuevo'),
    ('MEJORA', 'Mejoras'),
    ('SEGURIDAD', 'Seguridad'),
    ('ARREGLO', 'Correcciones'),
]


def literal(texto, i):
    """Lee un string de Kotlin que empieza en texto[i] == '"'. Devuelve (valor, fin)."""
    assert texto[i] == '"'
    i += 1
    out = []
    while i < len(texto):
        c = texto[i]
        if c == '\\':
            sig = texto[i + 1]
            out.append({'n': '\n', 't': '\t', '"': '"', '\\': '\\', '$': '$'}.get(sig, sig))
            i += 2
            continue
        if c == '"':
            return ''.join(out), i + 1
        out.append(c)
        i += 1
    raise ValueError('string sin cerrar')


def cadena(texto, i):
    """Un string, o varios unidos con `+`. Devuelve (valor, fin)."""
    valor, i = literal(texto, i)
    while True:
        m = re.match(r'\s*\+\s*', texto[i:])
        if not m or texto[i + m.end()] != '"':
            return valor, i
        siguiente, i = literal(texto, i + m.end())
        valor += siguiente


def leer_versiones(fuente):
    versiones = []
    # Solo las entradas (`NotasVersion(version = ...`), no la declaracion de la
    # clase, que tambien se llama asi.
    for m in re.finditer(r'NotasVersion\(\s*version\s*=', fuente):
        bloque_desde = m.start() + len('NotasVersion(')
        mv = re.compile(r'version\s*=\s*"').search(fuente, bloque_desde)
        version, _ = literal(fuente, mv.end() - 1)
        mf = re.compile(r'fecha\s*=\s*"').search(fuente, bloque_desde)
        fecha, _ = literal(fuente, mf.end() - 1)
        # Los cambios de esta version: hasta el proximo NotasVersion.
        sig = re.compile(r'NotasVersion\(\s*version\s*=').search(fuente, bloque_desde)
        hasta = sig.start() if sig else -1
        hasta = len(fuente) if hasta < 0 else hasta
        cambios = []
        for mc in re.finditer(r'Cambio\(TipoCambio\.([A-Z]+)\s*,\s*"', fuente[bloque_desde:hasta]):
            ini = bloque_desde + mc.end() - 1
            texto, _ = cadena(fuente, ini)
            cambios.append((mc.group(1), texto))
        versiones.append((version, fecha, cambios))
    return versiones


def fecha_iso(fecha):
    m = re.match(r'^(\d{2})/(\d{2})/(\d{4})$', fecha)
    return f'{m.group(3)}-{m.group(2)}-{m.group(1)}' if m else fecha


def generar():
    fuente = io.open(FUENTE, encoding='utf-8').read()
    versiones = leer_versiones(fuente)
    if not versiones:
        sys.exit('No se encontro ninguna version en ' + FUENTE)

    lineas = [
        '# Historial de cambios',
        '',
        'Lo que cambió en cada versión de wtfuck, contado para quien usa la app.',
        '',
        '> Este archivo **se genera**: no se edita a mano. Sale de la pantalla de',
        '> Novedades de la app (`app/src/main/java/com/wtfuck/app/ui/NovedadesPantalla.kt`)',
        '> con `python despliegue/changelog.py`. Las notas "Para desplegar" salen de',
        '> `despliegue/notas/<versión>.md`. El detalle técnico de cada cambio está en',
        '> el historial de git y en `docs/evidencias/`.',
        '',
    ]
    for version, fecha, cambios in versiones:
        lineas.append(f'## {version} · {fecha_iso(fecha)}')
        lineas.append('')
        for clave, titulo in SECCIONES:
            de_esta = [t for (c, t) in cambios if c == clave]
            if not de_esta:
                continue
            lineas.append(f'### {titulo}')
            lineas.append('')
            lineas.extend(f'- {t}' for t in de_esta)
            lineas.append('')
        desconocidos = [c for (c, _) in cambios if c not in dict(SECCIONES)]
        if desconocidos:
            sys.exit(f'Tipo de cambio sin seccion en {version}: {sorted(set(desconocidos))}')
        nota = os.path.join(NOTAS, f'{version}.md')
        if os.path.exists(nota):
            lineas.append('### Para desplegar')
            lineas.append('')
            lineas.append(io.open(nota, encoding='utf-8').read().strip())
            lineas.append('')
    io.open(SALIDA, 'w', encoding='utf-8', newline='\n').write('\n'.join(lineas).rstrip() + '\n')
    total = sum(len(c) for (_, _, c) in versiones)
    print(f'CHANGELOG.md: {len(versiones)} versiones, {total} cambios.')


if __name__ == '__main__':
    generar()
