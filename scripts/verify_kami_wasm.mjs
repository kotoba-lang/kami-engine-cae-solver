import fs from "node:fs";

const [wasmPath, hostPath] = process.argv.slice(2);
if (!wasmPath || !hostPath) {
  throw new Error("usage: node verify_kami_wasm.mjs <wasm-path> <host-js-path>");
}

const module = new WebAssembly.Module(fs.readFileSync(wasmPath));
const imports = WebAssembly.Module.imports(module);
const host = fs.readFileSync(hostPath, "utf8");
const missing = imports.filter(({ module: moduleName, name }) =>
  !host.includes(`"${moduleName}"`) || !host.includes(`"${name}"`),
);

if (missing.length) {
  throw new Error(`Kami WASM imports missing from browser host: ${JSON.stringify(missing)}`);
}

console.log(`Kami WASM import contract verified (${imports.length} imports)`);
