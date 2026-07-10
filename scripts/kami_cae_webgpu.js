/*
 * Kami Engine browser host for the CAE Pages report.
 *
 * Loads the shipped kami-clj demo WASM module, hosts its KAMI imports, and
 * uses its draw-mesh calls to animate a WebGPU dashboard. The DOM overlay is
 * provided by the unmodified Kami UI SDK loaded by the Pages workflow.
 */
(async () => {
  const status = document.getElementById("kami-runtime-status");
  const canvas = document.getElementById("kami-webgpu-canvas");
  const ticks = document.getElementById("kami-wasm-ticks");
  const draws = document.getElementById("kami-wasm-draws");

  if (!navigator.gpu) {
    status.textContent = "WebGPU is unavailable in this browser; the CLJS verification report remains available below.";
    status.dataset.state = "fallback";
    return;
  }

  let memory = null;
  let tick = 0;
  let drawCalls = 0;
  let initialized = false;
  let instance = null;
  const entities = new Map();
  const readString = (ptr, len) => new TextDecoder().decode(new Uint8Array(memory.buffer, ptr, len));
  const entity = (id) => entities.get(Number(id));

  const imports = {
    "kami:engine/scene@1.0.0": {
      "spawn": (_ptr, _len) => { const id = entities.size + 1; entities.set(id, {x: 0, y: 0, z: 0, vx: 0, vy: 0, vz: 0}); return BigInt(id); },
      "despawn": (id) => entities.delete(Number(id)),
      "get-x": (id) => entity(id)?.x || 0, "get-y": (id) => entity(id)?.y || 0, "get-z": (id) => entity(id)?.z || 0,
      "set-position": (id, x, y, z) => { const e = entity(id); if (e) Object.assign(e, {x, y, z}); },
      "get-vx": (id) => entity(id)?.vx || 0, "get-vy": (id) => entity(id)?.vy || 0, "get-vz": (id) => entity(id)?.vz || 0,
      "set-velocity": (id, vx, vy, vz) => { const e = entity(id); if (e) Object.assign(e, {vx, vy, vz}); },
      "get-rx": () => 0, "get-ry": () => 0, "get-rz": () => 0, "get-rw": () => 1,
      "set-rotation": () => {},
    },
    "kami:engine/physics@1.0.0": { "apply-impulse": () => {}, "apply-force": () => {}, "raycast": () => BigInt(0) },
    "kami:engine/input@1.0.0": { "key-down": () => 0, "key-pressed": () => 0, "axis": () => 0, "pointer-x": () => 0, "pointer-y": () => 0 },
    "kami:engine/render@1.0.0": {
      "draw-mesh": (ptr, len) => { if (memory) { readString(ptr, len); } drawCalls++; },
      "spawn-particle": () => {}, "draw-line": () => {},
    },
    "kami:engine/audio@1.0.0": { "play": () => {}, "stop": () => {}, "play-at": () => {} },
    "kami:engine/time@1.0.0": {
      "delta-ms": () => BigInt(16), "elapsed-ms": () => BigInt(Math.round(performance.now())), "tick": () => BigInt(tick),
    },
  };

  try {
    const response = await fetch("./kami-clj-demo.wasm");
    if (!response.ok) throw new Error(`WASM fetch returned HTTP ${response.status}`);
    ({instance} = await WebAssembly.instantiate(await response.arrayBuffer(), imports));
    memory = instance.exports.memory;
    instance.exports.init();
    initialized = true;
  } catch (error) {
    console.error(error);
    status.textContent = `WASM host failed: ${error.message}`;
    status.dataset.state = "error";
    return;
  }

  const adapter = await navigator.gpu.requestAdapter();
  if (!adapter) throw new Error("No WebGPU adapter available");
  const device = await adapter.requestDevice();
  const context = canvas.getContext("webgpu");
  const format = navigator.gpu.getPreferredCanvasFormat();
  const shader = device.createShaderModule({code: `
    struct Uniforms { size: vec2f, time: f32, signal: f32 }
    @group(0) @binding(0) var<uniform> u: Uniforms;
    @vertex fn vs(@builtin(vertex_index) index: u32) -> @builtin(position) vec4f {
      var p = array<vec2f, 3>(vec2f(-1.0, -1.0), vec2f(3.0, -1.0), vec2f(-1.0, 3.0));
      return vec4f(p[index], 0.0, 1.0);
    }
    @fragment fn fs(@builtin(position) p: vec4f) -> @location(0) vec4f {
      let uv = p.xy / u.size;
      let grid = step(0.985, fract(uv.x * 16.0)) + step(0.985, fract(uv.y * 10.0));
      let wave = 0.20 + 0.12 * sin(uv.x * 18.0 + u.time * 0.002);
      let trace = smoothstep(0.018, 0.0, abs(uv.y - (0.54 - wave * sin(uv.x * 11.0 + u.time * 0.003))));
      let meshSignal = smoothstep(0.0, 1.0, fract(u.signal * 0.11));
      let base = vec3f(0.025, 0.055, 0.11);
      let color = base + grid * vec3f(0.03, 0.10, 0.15) + trace * vec3f(0.05, 0.88, 0.62) + meshSignal * vec3f(0.02, 0.06, 0.08);
      return vec4f(color, 1.0);
    }`});
  const uniform = device.createBuffer({size: 16, usage: GPUBufferUsage.UNIFORM | GPUBufferUsage.COPY_DST});
  const pipeline = device.createRenderPipeline({layout: "auto", vertex: {module: shader, entryPoint: "vs"}, fragment: {module: shader, entryPoint: "fs", targets: [{format}]}, primitive: {topology: "triangle-list"}});
  const bindGroup = device.createBindGroup({layout: pipeline.getBindGroupLayout(0), entries: [{binding: 0, resource: {buffer: uniform}}]});

  const render = (time) => {
    const rect = canvas.getBoundingClientRect();
    const scale = Math.min(window.devicePixelRatio || 1, 2);
    const width = Math.max(1, Math.floor(rect.width * scale));
    const height = Math.max(1, Math.floor(rect.height * scale));
    if (canvas.width !== width || canvas.height !== height) {
      canvas.width = width; canvas.height = height;
      context.configure({device, format, alphaMode: "opaque"});
    }
    if (initialized) { instance.exports.tick(BigInt(16)); tick++; }
    device.queue.writeBuffer(uniform, 0, new Float32Array([width, height, time, drawCalls]));
    const encoder = device.createCommandEncoder();
    const pass = encoder.beginRenderPass({colorAttachments: [{view: context.getCurrentTexture().createView(), loadOp: "clear", storeOp: "store", clearValue: {r: 0.02, g: 0.04, b: 0.08, a: 1}}]});
    pass.setPipeline(pipeline); pass.setBindGroup(0, bindGroup); pass.draw(3); pass.end();
    device.queue.submit([encoder.finish()]);
    ticks.textContent = tick.toString(); draws.textContent = drawCalls.toString();
    requestAnimationFrame(render);
  };

  if (window.KamiUI) {
    window.KamiUI.init({bg: "#eff4f0"});
    window.KamiUI.StatusBar({text: "KAMI ENGINE · WebGPU + WASM", position: "top-left"});
    window.KamiUI.ControlHint({hints: [{key: "WASM", action: "compiled CLJ tick"}, {key: "WebGPU", action: "live renderer"}], position: "bottom-left"});
  }
  status.textContent = "WebGPU rendering · Kami Engine WASM host active";
  status.dataset.state = "ok";
  requestAnimationFrame(render);
})().catch((error) => {
  console.error(error);
  const status = document.getElementById("kami-runtime-status");
  if (status) { status.textContent = `WebGPU initialization failed: ${error.message}`; status.dataset.state = "error"; }
});
