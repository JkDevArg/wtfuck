import { useEffect, useState } from 'react';

/**
 * El circulo del avatar: la foto si existe, las iniciales si no.
 *
 * `cargar` baja la foto (fetch con token -> blob -> object URL) y `clave` es su
 * identidad estable: el efecto solo se repite cuando cambia la clave, no en cada
 * render. Sin foto -o mientras baja- se ven las iniciales, que es lo que la web
 * mostraba siempre; esto solo le suma la foto encima cuando la hay.
 */
export function Avatar({
  nombre,
  clave,
  cargar,
}: {
  nombre: string;
  clave: string;
  cargar: () => Promise<string | null>;
}) {
  const [url, setUrl] = useState<string | null>(null);
  useEffect(() => {
    let vivo = true;
    setUrl(null);
    cargar()
      .then((u) => {
        if (vivo) setUrl(u);
      })
      .catch(() => {});
    return () => {
      vivo = false;
    };
    // `clave` captura la identidad del recurso; `cargar` se recrea en cada
    // render y no debe disparar el efecto.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [clave]);

  if (url) {
    return (
      <span className="avatar">
        <img src={url} alt="" />
      </span>
    );
  }
  return (
    <span className="avatar" aria-hidden="true">
      {nombre.slice(0, 2).toUpperCase()}
    </span>
  );
}
