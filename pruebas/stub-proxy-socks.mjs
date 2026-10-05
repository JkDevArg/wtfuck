// Un proxy SOCKS5 minimo, para probar el ajuste de proxy de la app.
//
// No es parte de la suite: se levanta a mano y escribe en la consola a donde
// pide conectar la app. Sin autenticacion, como Tor u Orbot.
//
//   node pruebas/stub-proxy-socks.mjs [puerto] [mapeo...]
//   node pruebas/stub-proxy-socks.mjs 1080 8088=8300
//
// El mapeo existe por el entorno de desarrollo: la app de debug habla con
// 127.0.0.1:8088 -un tunel de `adb reverse`-, y desde la PC ese puerto es el
// 8300 del servidor. Con el mapeo, "127.0.0.1:8088" se conecta al 8300 local.
import net from 'node:net';

const PUERTO = Number(process.argv[2] ?? 1080);
const MAPEO = new Map(process.argv.slice(3).map((m) => m.split('=').map(Number)));
let n = 0;

net.createServer((cli) => {
  const id = ++n;
  cli.once('data', (hola) => {
    // Saludo: version 5, metodos. Respondemos "sin autenticacion".
    if (hola[0] !== 5) return cli.destroy();
    cli.write(Buffer.from([5, 0]));
    cli.once('data', (pedido) => {
      // VER CMD RSV ATYP DST.ADDR DST.PORT; solo CONNECT (1).
      if (pedido[0] !== 5 || pedido[1] !== 1) return cli.destroy();
      let host, i;
      if (pedido[3] === 1) { host = [...pedido.subarray(4, 8)].join('.'); i = 8; }
      else if (pedido[3] === 3) { const l = pedido[4]; host = pedido.subarray(5, 5 + l).toString(); i = 5 + l; }
      else return cli.destroy();
      const puerto = pedido.readUInt16BE(i);
      const destino = MAPEO.get(puerto) ?? puerto;
      console.log(`[${new Date().toISOString().slice(11, 19)}] #${id} CONNECT ${host}:${puerto}` +
        (destino !== puerto ? ` -> 127.0.0.1:${destino}` : ''));
      const srv = net.connect(destino, MAPEO.has(puerto) ? '127.0.0.1' : host, () => {
        cli.write(Buffer.from([5, 0, 0, 1, 0, 0, 0, 0, 0, 0]));
        cli.pipe(srv); srv.pipe(cli);
      });
      srv.on('error', () => { cli.end(Buffer.from([5, 1, 0, 1, 0, 0, 0, 0, 0, 0])); });
      cli.on('error', () => srv.destroy());
    });
  });
}).listen(PUERTO, '0.0.0.0', () => console.log(`SOCKS5 en 0.0.0.0:${PUERTO}`, MAPEO.size ? MAPEO : ''));
