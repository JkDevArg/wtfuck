const BASE='http://localhost:8300'; const S=Math.random().toString(36).slice(2,7);
let ok=0,fail=0; const ck=(n,c,x='')=>{c?(ok++,console.log('  PASA  '+n)):(fail++,console.log('  FALLA '+n+' '+x))};
const b64=s=>Buffer.from(s).toString('base64');
const r=await fetch(BASE+'/v1/registro',{method:'POST',headers:{'Content-Type':'application/json'},
  body:JSON.stringify({username:'perf'+S,password:'clave-larga-123',etiquetaDispositivo:'t',
  identidadPub:b64('k'),hardwareHash:b64('HW'+S),hardwareNivel:'SOFTWARE_DEV'})});
const {token}=await r.json(); const H={Authorization:'Bearer '+token};

let p=await (await fetch(BASE+'/v1/perfil',{headers:H})).json();
ck('perfil arranca vacio', p.nombreMostrado==='' && p.avatarVersion===0);

p=await (await fetch(BASE+'/v1/perfil',{method:'PUT',headers:{...H,'Content-Type':'application/json'},
  body:JSON.stringify({nombreMostrado:'Joaquin C.',estadoTexto:'Disponible'})})).json();
ck('guardar nombre y estado', p.nombreMostrado==='Joaquin C.' && p.estadoTexto==='Disponible');

// PNG minimo valido
const png=Buffer.concat([Buffer.from([0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A]),Buffer.alloc(64,7)]);
let up=await fetch(BASE+'/v1/perfil/avatar',{method:'PUT',headers:H,body:png});
const upj=await up.json();
ck('subir avatar PNG', up.status===200 && upj.avatarVersion>0, JSON.stringify(upj));

const noImg=await fetch(BASE+'/v1/perfil/avatar',{method:'PUT',headers:H,body:Buffer.from('MZ ejecutable disfrazado')});
ck('rechaza archivo que no es imagen', noImg.status===400, String(noImg.status));

const grande=await fetch(BASE+'/v1/perfil/avatar',{method:'PUT',headers:H,body:Buffer.concat([Buffer.from([0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A]),Buffer.alloc(600000,1)])});
ck('rechaza avatar mayor a 512KB', grande.status===413, String(grande.status));

const img=await fetch(`${BASE}/v1/usuarios/perf${S}/avatar`,{headers:H});
const bytes=Buffer.from(await img.arrayBuffer());
ck('descargar avatar devuelve los mismos bytes', img.status===200 && bytes.equals(png));
ck('el avatar se sirve con cache inmutable', (img.headers.get('cache-control')||'').includes('immutable'));

const sinAuth=await fetch(`${BASE}/v1/usuarios/perf${S}/avatar`);
ck('sin token no se puede ver el avatar', sinAuth.status===401, String(sinAuth.status));

const raro=await fetch(`${BASE}/v1/usuarios/perf${S}/password_hash`,{headers:H});
ck('campo arbitrario rechazado (no hay inyeccion de columna)', raro.status===404, String(raro.status));

const port=await fetch(BASE+'/v1/perfil/portada',{method:'PUT',headers:H,
  body:Buffer.concat([Buffer.from('RIFF'),Buffer.alloc(4),Buffer.from('WEBP'),Buffer.alloc(64,3)])});
ck('subir portada WEBP', port.status===200 && (await port.json()).portadaVersion>0);

const busca=await (await fetch(`${BASE}/v1/usuarios/perf${S}`,{headers:H})).json();
ck('buscar usuario trae nombre y version de avatar', busca.nombreMostrado==='Joaquin C.' && busca.avatarVersion>0);

console.log(`\n  PASAN: ${ok}   FALLAN: ${fail}\n`);
process.exit(fail===0?0:1);
