import fs from "node:fs";

const html = fs.readFileSync(process.argv[2] || "dist/index.html", "utf8");
const js = fs.readFileSync(process.argv[3] || "scripts/kami_cae_webgpu.js", "utf8");
const ids = [...html.matchAll(/data-kami-scene="(\d+)"/g)].map((m) => Number(m[1]));
const expected = Array.from({length: 15}, (_, i) => i);
if (JSON.stringify(ids) !== JSON.stringify(expected)) throw new Error(`scene buttons are not contiguous: ${ids}`);
for (const id of expected.slice(1)) {
  if (!js.includes(`kind === ${id}`)) throw new Error(`missing WebGPU geometry for scene ${id}`);
}
for (const metric of ["Sod FVM", "RANS k–ε", "Thermo-mechanical", "3D contact", "Fracture", "MPI"]) {
  if (!html.includes(metric)) throw new Error(`missing advanced metric: ${metric}`);
}
console.log(`Advanced WebGPU scene contract verified (${ids.length} scenes)`);
