# kami-engine-cae-solver

[![CI](https://github.com/kotoba-lang/kami-engine-cae-solver/actions/workflows/ci.yml/badge.svg)](https://github.com/kotoba-lang/kami-engine-cae-solver/actions/workflows/ci.yml)

The CAE solver **contract**: one `solve` multimethod dispatching on `[:solver :kind]`, so a case is answered by a portable reduced-order backend today and a dedicated high-fidelity host adapter tomorrow with no caller change.

`cae.industrial` provides deterministic, zero-dependency reference backends:

| kind | Coverage |
| --- | --- |
| `:cfd` | duct/ventilation flow, straight/fitting pressure-loss breakdown, fan power, heat removal, steady combustion heat source |
| `:fem` | axial bar / cantilever beam deformation, stress, first mode, fatigue life |
| `:process` | welding heat input, casting energy/Chvorinov solidification time, rolling force |
| `:materials` | constant-rate / Arrhenius-JMAK phase transformation and resulting hardness |
| `:emag` | three-phase motor power/torque/losses and induction-heating workpiece temperature rise |
| `:production-des` | discrete-event throughput, utilisation, waiting, transport, lead time, WIP and busy-time energy |

Load `cae.industrial` before dispatching so its methods are registered:

```clojure
(require '[cae.industrial] '[cae.solver :as cae])
(cae/solve {:solver {:kind :cfd}
            :flow-m3-s 1.2 :duct-diameter-m 0.4 :duct-length-m 20.0})
```

Every result identifies its `:fidelity` (`:reduced-order`), `:status`
(`:screening-only`), `:model`, SI unit system, and modelling assumptions; an
optional `:case/id` is carried through unchanged. Inputs are validated for
finite values and physical bounds before a result is calculated. These models
are therefore suitable for early design-space exploration and simulation
orchestration, but not as a substitute for validated, high-fidelity CAE or
manufacturing release signoff.

For a design sweep or a set of factory scenarios, `cae.orchestration/run-cases`
isolates each failure and retains the input order. It returns one envelope per
case (`:succeeded` with `:result`, or `:failed` with data-only `:error`), while
`summary` reports total, succeeded, failed, and solver-kind counts. This makes
the reference solvers safe to use as one branch of a larger CAD/BIM → OpenUSD
or production-planning workflow.

`cae.interchange/attach-provenance` binds a validated source reference to a
case. It supports `:cad`, `:bim`, `:equipment`, and `:openusd`; OpenUSD inputs
require both an asset URI and absolute prim path. The source URI, revision,
coordinate convention, and scale are copied into every solver result, making
the model used for a screening result explicit without making this library a
CAD/BIM/USD parser.

`cae.assessment/assess` evaluates solver outputs against explicit engineering
limits such as `{:pressure-drop-Pa {:max 500.0}}` or
`{:safety-factor {:min 1.5}}`. Missing and non-finite values are always
failed, while invalid acceptance configuration is rejected separately. The
assessment retains case, solver, model, and provenance information so it can
serve as auditable design-review or manufacturing-gate evidence.

For design-space exploration, `cae.study/parameter-sweep` varies a nested case
parameter while preserving input order and isolating invalid points.
`central-sensitivity` produces a traceable central finite-difference derivative
for any finite result metric. Both work against the common solver contract, so
the study remains valid when a host replaces a screening model with a dedicated
CAE adapter.

Part of the clean-sheet vehicle-design / CAE stack (purpose-split shared libs).
Zero-dep portable `.cljc`. Run `clojure -M:test`.

## CLJS / Node smoke test

The same source executes under ClojureScript through NBB (no JVM solver
fallback). With [NBB](https://github.com/babashka/nbb) installed, run:

```sh
nbb -cp src:test -e "(require '[cae.cljs-smoke-test])"
```

It executes CFD (fittings + combustion), FEM beam, Arrhenius materials,
induction heating, production energy, OpenUSD provenance, assessment, batch
failure isolation, and sensitivity analysis in the Node/CLJS runtime.

## High-fidelity reference contracts

`cae.high-fidelity` and `cae.verification` expose portable numerical reference
kernels behind the same `cae.solver/solve` dispatch:

| kind | Contract |
| --- | --- |
| `:fvm-compressible` | 1D conservative Euler finite-volume update with Rusanov flux; `:sod-shock-tube` initial condition and density/pressure profiles |
| `:rans-k-epsilon` | 1D k–epsilon transport reference with diffusion, production, dissipation and standard constants |
| `:combustion-reaction` / `:boundary-layer` | Arrhenius reaction and laminar/turbulent boundary-layer correlations |
| `:fem-elastoplastic` / `:finite-strain-elastic` | return-mapping plasticity and finite-strain Neo-Hookean reference response |
| `:friction-contact` / `:friction-contact-3d` / `:fracture-criterion` | penalty contact, Coulomb friction cone and stress-intensity failure criterion |
| `:fem-mesh` / `:mesh-quality` / `:adaptive-mesh` | TRI3/TET4 generation, area/volume quality and gradient-marked refinement |
| `:mpi-domain` / `:mpi-halo-exchange` / `:mpi-load-balance` | deterministic partition, ghost indices, halo synchronization and weighted rank balancing |
| `:material-database` | temperature-dependent SS304, Al6061, water and air properties |
| `:benchmark-suite` / `:benchmark-catalog` / `:experimental-comparison` / `:validation-report` | analytic Poiseuille/bar/wall checks, Sod/Taylor/cantilever metadata, experimental RMSE/bias and aggregate regression gate |

These kernels are deliberately labelled `:fidelity :*-reference` and
`:status :screening-only`. They define a deterministic CLJ/CLJS boundary for
swapping in validated CFD/FEM/MPI implementations; they do not claim commercial
solver verification or manufacturing release authority.

For a host-native validated solver, use `cae.adapter` with
`:solver {:kind :external-backend}`. The descriptor records backend, version,
domain, input format, command/MPI transport and result provenance, while this
portable library remains process-neutral. A missing host result is reported as
`:adapter-pending`; a returned result is marked `:completed` without silently
promoting its fidelity.

## GitHub Pages WebGPU view

The Pages report compiles `cae.webgpu` from ClojureScript and drives the browser
WebGPU API directly through CLJS-to-JavaScript interop. Scene selection, GPU
buffers, WGSL pipelines, render passes, frames, and draw counters no longer
cross a per-frame Wasm host boundary. Wasm remains an appropriate optional
backend for large solver hot loops, but is not loaded for UI/render dispatch.
All 15 scenes have time-dependent state, realtime geometry regeneration,
pause/resume, reset, speed control, and live telemetry. The Sod shock-tube scene
steps the portable finite-volume kernel; the other scenes currently use
stateful reduced-order/analytic dynamics and must not be read as commercial CAE
validation results. The report DOM is generated from Hiccup2 and its minified,
zero-runtime stylesheet is extracted by Shadow CSS.
Clicking or dragging on the canvas applies a scene-specific action (for example
an inlet impulse, point load, heat/current source, contact force, crack-tip
load, job injection, or mesh perturbation). Its position and decaying strength
are part of the simulation state; the Sod scene additionally injects momentum
and energy into the selected FVM cell before subsequent solver steps.
The single-screen interface keeps scene navigation, the simulation viewport,
controls, and response telemetry visible together. A dashed action boundary,
crosshair, plain-language instruction, and pointer-following action ring make
the interactive region perceivable without relying on color alone.
If WebGPU is unavailable, the CAE report remains readable and reports the
fallback state rather than pretending to render.
