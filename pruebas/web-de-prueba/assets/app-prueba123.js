// Un archivo de assets/ con hash en el nombre, como los que arma Vite: el
// servidor lo tiene que mandar con cache "immutable".
document.getElementById('estado').textContent = 'wtfuck web de prueba: el script cargo';
// Como la app de verdad, el script nombra su WebAssembly: pruebas/web.mjs lo
// busca aqui. new URL('/web/assets/vacio-prueba123.wasm', location.href)
