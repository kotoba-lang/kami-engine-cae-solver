/* Kami Engine WASM host + browser-native WebGPU 3D scene viewer. */
(async () => {
  const status = document.getElementById("kami-runtime-status");
  const canvas = document.getElementById("kami-webgpu-canvas");
  const ticks = document.getElementById("kami-wasm-ticks");
  const draws = document.getElementById("kami-wasm-draws");
  const sceneName = document.getElementById("kami-scene-name");
  const sceneLabels = ["CFD · flow / combustion", "FEM · cantilever beam", "Process · weld / cast / roll", "Materials · microstructure", "EM · motor / induction", "Production · flow line"];
  let scene = 0;
  document.querySelectorAll("[data-kami-scene]").forEach((button) => button.addEventListener("click", () => {
    scene = Number(button.dataset.kamiScene);
    document.querySelectorAll("[data-kami-scene]").forEach((b) => b.classList.toggle("active", b === button));
    sceneName.textContent = sceneLabels[scene];
    geometryBuffer = null;
  }));

  if (!navigator.gpu) {
    status.textContent = "WebGPU unavailable; choose a WebGPU-capable browser to view 3D scenes.";
    status.dataset.state = "fallback";
    return;
  }

  let memory = null, tick = 0, drawCalls = 0, instance = null, geometryBuffer = null, geometryCount = 0;
  const entities = new Map();
  const readString = (ptr, len) => new TextDecoder().decode(new Uint8Array(memory.buffer, ptr, len));
  const entity = (id) => entities.get(Number(id));
  const imports = {
    "kami:engine/scene@1.0.0": {
      spawn: (_p, _l) => { const id = entities.size + 1; entities.set(id, {x: 0, y: 0, z: 0, vx: 0, vy: 0, vz: 0}); return BigInt(id); },
      despawn: (id) => entities.delete(Number(id)), "get-x": (id) => entity(id)?.x || 0, "get-y": (id) => entity(id)?.y || 0, "get-z": (id) => entity(id)?.z || 0,
      "set-position": (id, x, y, z) => { const e = entity(id); if (e) Object.assign(e, {x, y, z}); }, "get-vx": (id) => entity(id)?.vx || 0, "get-vy": (id) => entity(id)?.vy || 0, "get-vz": (id) => entity(id)?.vz || 0,
      "set-velocity": (id, vx, vy, vz) => { const e = entity(id); if (e) Object.assign(e, {vx, vy, vz}); }, "get-rx": () => 0, "get-ry": () => 0, "get-rz": () => 0, "get-rw": () => 1, "set-rotation": () => {},
    },
    "kami:engine/physics@1.0.0": { "apply-impulse": () => {}, "apply-force": () => {}, raycast: () => BigInt(0) },
    "kami:engine/input@1.0.0": { "key-down": () => 0, "key-pressed": () => 0, axis: () => 0, "pointer-x": () => 0, "pointer-y": () => 0 },
    "kami:engine/render@1.0.0": { "draw-mesh": (ptr, len) => { if (memory) readString(ptr, len); drawCalls++; }, "spawn-particle": () => {}, "draw-line": () => {} },
    "kami:engine/audio@1.0.0": { "play": () => {}, stop: () => {}, "play-at": () => {} },
    "kami:engine/time@1.0.0": { "delta-ms": () => BigInt(16), "elapsed-ms": () => BigInt(Math.round(performance.now())), tick: () => BigInt(tick) },
  };
  try {
    const response = await fetch("./kami-clj-demo.wasm");
    if (!response.ok) throw new Error(`WASM fetch returned HTTP ${response.status}`);
    ({instance} = await WebAssembly.instantiate(await response.arrayBuffer(), imports));
    memory = instance.exports.memory; instance.exports.init();
  } catch (error) {
    console.error(error); status.textContent = `WASM host failed: ${error.message}`; status.dataset.state = "error"; return;
  }

  const adapter = await navigator.gpu.requestAdapter();
  if (!adapter) throw new Error("No WebGPU adapter available");
  const device = await adapter.requestDevice();
  const context = canvas.getContext("webgpu");
  const format = navigator.gpu.getPreferredCanvasFormat();
  const shader = device.createShaderModule({code: `
    struct U { mvp: mat4x4f, params: vec4f }
    @group(0) @binding(0) var<uniform> u: U;
    struct V { @location(0) position: vec3f, @location(1) color: vec3f }
    struct O { @builtin(position) position: vec4f, @location(0) color: vec3f }
    @vertex fn vs(v: V) -> O { var o: O; o.position = u.mvp * vec4f(v.position, 1.0); o.color = v.color; return o; }
    @fragment fn fs(o: O) -> @location(0) vec4f { return vec4f(o.color, 1.0); }
  `});
  const pipeline = device.createRenderPipeline({
    layout: "auto", vertex: {module: shader, entryPoint: "vs", buffers: [{arrayStride: 24, attributes: [{shaderLocation: 0, offset: 0, format: "float32x3"}, {shaderLocation: 1, offset: 12, format: "float32x3"}]}]},
    fragment: {module: shader, entryPoint: "fs", targets: [{format}]}, primitive: {topology: "triangle-list", cullMode: "back"}, depthStencil: {format: "depth24plus", depthWriteEnabled: true, depthCompare: "less"},
  });
  const uniform = device.createBuffer({size: 80, usage: GPUBufferUsage.UNIFORM | GPUBufferUsage.COPY_DST});
  const bindGroup = device.createBindGroup({layout: pipeline.getBindGroupLayout(0), entries: [{binding: 0, resource: {buffer: uniform}}]});
  let depthView;
  const color = {cyan: [0.04, 0.85, 0.68], blue: [0.10, 0.42, 0.95], orange: [1.0, 0.35, 0.12], gold: [1.0, 0.75, 0.12], purple: [0.62, 0.28, 0.95], white: [0.75, 0.88, 0.95]};
  const cube = (out, x, y, z, sx, sy, sz, c) => {
    const x0 = x - sx / 2, x1 = x + sx / 2, y0 = y - sy / 2, y1 = y + sy / 2, z0 = z - sz / 2, z1 = z + sz / 2;
    const faces = [[x0,y0,z1,x1,y0,z1,x1,y1,z1,x0,y1,z1],[x1,y0,z0,x0,y0,z0,x0,y1,z0,x1,y1,z0],[x0,y1,z1,x1,y1,z1,x1,y1,z0,x0,y1,z0],[x0,y0,z0,x1,y0,z0,x1,y0,z1,x0,y0,z1],[x1,y0,z1,x1,y0,z0,x1,y1,z0,x1,y1,z1],[x0,y0,z0,x0,y0,z1,x0,y1,z1,x0,y1,z0]];
    for (const q of faces) { const ix = [0,1,2,0,2,3]; for (const i of ix) out.push(q[i*3], q[i*3+1], q[i*3+2], c[0], c[1], c[2]); }
  };
  const sceneGeometry = (kind) => {
    const v = [];
    if (kind === 0) { cube(v, 0, 0, 0, 5.8, 1.5, 1.5, color.blue); for (let i=0;i<6;i++) cube(v, -2.3 + i*.9, .1, 0, .25, .25, .25, color.cyan); cube(v, -3.5, 0, 0, .3, 2.6, 2.6, color.white); }
    if (kind === 1) { cube(v, -2, -1, 0, .8, 2.5, 1.2, color.white); cube(v, .3, .1, 0, 4.8, .35, .65, color.cyan); cube(v, 2.5, -.8, 0, .3, 1.6, .3, color.orange); cube(v, 2.5, -1.7, 0, .8, .2, .8, color.orange); }
    if (kind === 2) { cube(v, -2, -.7, 0, .7, 1.4, 1.4, color.orange); cube(v, 0, -.7, 0, .7, 1.4, 1.4, color.orange); cube(v, 2, -.7, 0, .7, 1.4, 1.4, color.orange); cube(v, 0, .35, 0, 5.4, .25, 1.2, color.gold); cube(v, 0, .8, 0, 2.0, .12, .8, color.cyan); }
    if (kind === 3) { for (let x=0;x<4;x++) for (let y=0;y<3;y++) for (let z=0;z<3;z++) cube(v, (x-1.5)*.65, (y-1)*.65, (z-1)*.65, .3, .3, .3, [0.2+x*.12,0.35+y*.15,0.8+z*.05]); }
    if (kind === 4) { cube(v, 0, 0, 0, 1.3, 2.3, 1.3, color.purple); for (let i=0;i<12;i++) { const a=i*Math.PI/6; cube(v, Math.cos(a)*1.5, 0, Math.sin(a)*1.5, .25, 1.0, .25, color.cyan); } cube(v, 0, 0, 0, 3.8, .12, .12, color.gold); }
    if (kind === 5) { for (let i=0;i<4;i++) { cube(v, -2.4+i*1.6, -.5, 0, 1.0, 1.2, 1.3, [0.1+i*.12, .45, .8-i*.08]); cube(v, -2.4+i*1.6, .35, 0, .35, .35, .35, color.gold); } cube(v, 0, -1.2, 0, 6.0, .12, 1.5, color.white); }
    return new Float32Array(v);
  };
  const perspective = (fov, aspect, near, far) => { const f=1/Math.tan(fov/2), nf=1/(near-far); return new Float32Array([f/aspect,0,0,0, 0,f,0,0, 0,0,(far+near)*nf,-1, 0,0,2*far*near*nf,0]); };
  const lookAt = (eye, center, up) => { const z=norm(sub(eye,center)), x=norm(cross(up,z)), y=cross(z,x); return new Float32Array([x[0],y[0],z[0],0,x[1],y[1],z[1],0,x[2],y[2],z[2],0,-dot(x,eye),-dot(y,eye),-dot(z,eye),1]); };
  const sub=(a,b)=>[a[0]-b[0],a[1]-b[1],a[2]-b[2]], dot=(a,b)=>a[0]*b[0]+a[1]*b[1]+a[2]*b[2], cross=(a,b)=>[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]], norm=(a)=>{const l=Math.hypot(...a);return a.map(x=>x/l);};
  const mul = (a,b) => { const o=new Float32Array(16); for(let c=0;c<4;c++) for(let r=0;r<4;r++) o[c*4+r]=a[r]*b[c*4]+a[4+r]*b[c*4+1]+a[8+r]*b[c*4+2]+a[12+r]*b[c*4+3]; return o; };
  const render = (time) => {
    const rect=canvas.getBoundingClientRect(), scale=Math.min(devicePixelRatio||1,2), width=Math.max(1,Math.floor(rect.width*scale)), height=Math.max(1,Math.floor(rect.height*scale));
    if(canvas.width!==width||canvas.height!==height){canvas.width=width;canvas.height=height;context.configure({device,format,alphaMode:"opaque"});depthView=device.createTexture({size:[width,height,1],format:"depth24plus",usage:GPUTextureUsage.RENDER_ATTACHMENT}).createView();}
    if(!geometryBuffer){const data=sceneGeometry(scene);geometryCount=data.length/6;geometryBuffer=device.createBuffer({size:Math.max(4,data.byteLength),usage:GPUBufferUsage.VERTEX|GPUBufferUsage.COPY_DST});device.queue.writeBuffer(geometryBuffer,0,data);}
    instance.exports.tick(BigInt(16)); tick++;
    const angle=time*.00025, eye=[Math.sin(angle)*8,5,Math.cos(angle)*8], mvp=mul(perspective(Math.PI/4,width/height,.1,100),lookAt(eye,[0,0,0],[0,1,0]));
    const u=new Float32Array(20);u.set(mvp);u[16]=time;u[17]=drawCalls;u[18]=scene;device.queue.writeBuffer(uniform,0,u);
    const enc=device.createCommandEncoder(), pass=enc.beginRenderPass({colorAttachments:[{view:context.getCurrentTexture().createView(),loadOp:"clear",storeOp:"store",clearValue:{r:.015,g:.03,b:.06,a:1}}],depthStencilAttachment:{view:depthView,depthClearValue:1,depthLoadOp:"clear",depthStoreOp:"store"}});
    pass.setPipeline(pipeline);pass.setBindGroup(0,bindGroup);pass.setVertexBuffer(0,geometryBuffer);pass.draw(geometryCount);pass.end();device.queue.submit([enc.finish()]);ticks.textContent=tick;draws.textContent=drawCalls;requestAnimationFrame(render);
  };
  if(window.KamiUI){window.KamiUI.init({bg:"#eff4f0"});window.KamiUI.StatusBar({text:"KAMI ENGINE · WebGPU + WASM",position:"top-left"});}
  status.textContent="WebGPU 3D rendering · Kami Engine WASM host active";status.dataset.state="ok";requestAnimationFrame(render);
})().catch((error)=>{console.error(error);const s=document.getElementById("kami-runtime-status");if(s){s.textContent=`WebGPU initialization failed: ${error.message}`;s.dataset.state="error";}});
