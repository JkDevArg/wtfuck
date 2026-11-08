import { useEffect, useRef } from 'react';

// Cloudflare Turnstile. El widget se dibuja solo si el servidor manda una
// sitekey (ModoRegistro.captchaSitekey); si no, este componente no se monta.

interface TurnstileApi {
  render: (el: HTMLElement, opts: { sitekey: string; callback: (t: string) => void; 'error-callback'?: () => void; 'expired-callback'?: () => void }) => string;
  remove: (id: string) => void;
}
declare global {
  interface Window { turnstile?: TurnstileApi }
}

const URL_SCRIPT = 'https://challenges.cloudflare.com/turnstile/v0/api.js';

export function WidgetTurnstile({ sitekey, onToken }: { sitekey: string; onToken: (t: string) => void }) {
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    let id: string | undefined;
    let cancelado = false;

    const dibujar = () => {
      if (cancelado || !ref.current || !window.turnstile) return;
      id = window.turnstile.render(ref.current, {
        sitekey,
        callback: (t) => onToken(t),
        'error-callback': () => onToken(''),
        'expired-callback': () => onToken(''),
      });
    };

    if (window.turnstile) {
      dibujar();
    } else {
      let s = document.querySelector<HTMLScriptElement>('script[data-turnstile]');
      if (!s) {
        s = document.createElement('script');
        s.src = URL_SCRIPT;
        s.async = true;
        s.defer = true;
        s.setAttribute('data-turnstile', '1');
        document.head.appendChild(s);
      }
      s.addEventListener('load', dibujar, { once: true });
    }

    return () => {
      cancelado = true;
      if (id && window.turnstile) window.turnstile.remove(id);
    };
  }, [sitekey]);

  return <div ref={ref} style={{ marginTop: 8 }} />;
}
