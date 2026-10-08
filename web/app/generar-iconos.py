# Uso: python web/app/generar-iconos.py  (necesita Pillow). Los PNG van al repo.
# Los iconos de la PWA, dibujados del MISMO vector que el icono de la app
# (app/src/main/res/drawable/ic_launcher_foreground.xml, viewport 108x108):
# un globo de chat cian (#6CF8F6) con dos lineas, sobre #0E1313.
import os
from PIL import Image, ImageDraw

SALIDA = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'public', 'iconos')
os.makedirs(SALIDA, exist_ok=True)

FONDO = (0x0E, 0x13, 0x13, 255)
CIAN = (0x6C, 0xF8, 0xF6, 255)


def dibujar(lado, escala_contenido=1.0, redondeo=0):
    """`escala_contenido` < 1 encoge el globo hacia el centro (para maskable)."""
    sobre = 8  # se dibuja a 8x y se reduce: bordes suaves
    L = lado * sobre
    img = Image.new('RGBA', (L, L), FONDO if not redondeo else (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if redondeo:
        d.rounded_rectangle([0, 0, L - 1, L - 1], radius=int(L * redondeo), fill=FONDO)

    # Del viewport 108 al lienzo, centrado y con la escala pedida.
    k = L / 108 * escala_contenido
    off = (L - 108 * k) / 2

    def p(x, y):
        return (off + x * k, off + y * k)

    # Globo: rectangulo redondeado 26..82 x 34..70, radio 6, y la cola
    # (M32,34 h44 ... h-24 l-12,10 v-10 h-8 a6,6 -> el borde izquierdo es 26).
    x0, y0 = p(26, 34)
    x1, y1 = p(82, 70)
    d.rounded_rectangle([x0, y0, x1, y1], radius=6 * k, fill=CIAN)
    d.polygon([p(40, 69), p(52, 69), p(40, 80)], fill=CIAN)
    # Las dos lineas de texto.
    for (a, b, c, e) in [(44, 50, 64, 54), (44, 58, 58, 62)]:
        d.rectangle([*p(a, b), *p(c, e)], fill=FONDO)
    return img.resize((lado, lado), Image.LANCZOS)


# "any": el icono tal cual, a sangre (el sistema le pone su mascara).
for lado in (192, 512):
    dibujar(lado).save(f'{SALIDA}/icono-{lado}.png', optimize=True)
# "maskable": Android recorta hasta un circulo del 80%; el globo ya cae dentro
# porque el vector se diseno con la zona segura del icono adaptativo (66 de 108).
dibujar(512).save(f'{SALIDA}/icono-maskable-512.png', optimize=True)
# iPhone: apple-touch-icon de 180, opaco (iOS pone su propio redondeo).
dibujar(180).save(f'{SALIDA}/apple-touch-icon.png', optimize=True)
# Favicon.
dibujar(32).save(f'{SALIDA}/favicon-32.png', optimize=True)
print(sorted(os.listdir(SALIDA)))
