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

## Numerical V&V and qualification gates

`cae.vv` provides fail-closed evidence checks for a narrowly declared solver
scope. A passing `:qualification-gate` requires all of the following, not just
a regression test:

- an independently evaluated analytic/public benchmark;
- integral conservation with a scale-aware relative imbalance;
- a finite iterative residual history meeting both final tolerance and
  reduction requirements;
- a monotonic three-grid study with observed order, Richardson extrapolation,
  and fine-grid GCI below its declared tolerance;
- complete traceability for case, solver/version, model revision, input, mesh,
  execution time, and platform.

Missing or failed evidence returns `:status :not-qualified` and
`:claim :no-industrial-accuracy-claim`. Passing evidence returns only
`:verified-for-declared-scope`; it never promotes the whole package to a
commercial-fidelity claim. The Poiseuille and axial-bar analytic benchmarks
now execute the actual `:cfd` and `:fem` solver paths instead of copying the
analytic answer into the computed field.

The `:axial-bar-fe` benchmark assembles and solves a real linear-element
stiffness system for a sinusoidal distributed load. `:axial-bar-vv-study`
runs 8/16/32-element meshes and derives analytic error, force equilibrium,
algebraic residual reduction, observed order, Richardson extrapolation and
GCI from those solver runs. This scope can pass as
`:verified-for-declared-scope`; it does not qualify other FEM models.

The compressible FVM update is expressed as the difference of shared Rusanov
interface fluxes. Every run reports initial/final conserved quantities,
integrated boundary outflow, conservation defect and time-step update norms.
An explicit transient update norm is deliberately not mislabeled as an
iterative residual.

## Reproducible external datasets

`resources/cae/datasets.edn` pins Hugging Face repositories to immutable
40-character revisions and records license, origin, intended use, commercial
permission, validation independence, citation, split, byte size and SHA-256.
`clojure -M:dataset -m verify-external-dataset aethron-cfd-pinn` downloads only the
manifested files and rejects any size/hash drift. CI executes this byte-level
check on JDK 21.

Dataset provenance is not accuracy evidence by itself. `cae.dataset` rejects
synthetic and simulation-only datasets as independent experimental validation,
and rejects non-commercial licenses from a commercial qualification claim.
The current registry includes pinned metadata for RealPDEBench, PDEBench 1D
compressible flow, RAE2822 CFD and CMU SFEM. RealPDEBench contains paired real
and simulated trajectories, but its CC-BY-NC-4.0 license makes it unsuitable
for commercial qualification. The small pinned Aethron CFD CSV is downloaded
in CI, but remains training-only synthetic data.

The registry also content-pins the NIST MIDAS experimental compression data
for annealed AISI 1045 steel. Run
`clojure -M:dataset -m verify-external-dataset nist-midas-1045-dynamic-plasticity`
to download the NIST CSV over HTTPS, reject byte/hash drift, parse its experiment
blocks, and reproduce paired published-model residual statistics. The source
does not publish per-sample measurement uncertainty, so the resulting report is
explicitly a calibration correlation—not independent validation or a released
production material card.

`resources/cae/qualification-matrix.edn` separately tracks numerical
verification, independent experimental validation and software quality for
each applicability scope. `cae.vv/industrial-release-gate` requires all three,
complete traceability, and explicit included/excluded conditions; numerical
verification alone cannot produce an industrial release claim.

The registry also pins the NASA/TMBWG Smooth-Body Separation Experiment OFI
measurements at a fixed Git commit under CC0-1.0. CI verifies the repository
license and three Mach-condition data files by SHA-256 and byte count, parses
the measured skin-friction coefficient together with each sample's `e_Cf`
uncertainty, and marks the dataset itself eligible for commercial independent
validation. The corresponding RANS solver scope remains not release-qualified:
qualified experimental data is now available, but this package does not yet
produce a verified prediction for that geometry. `experimental-validation-check`
performs normalized-RMSE and uncertainty-envelope coverage checks when such
predictions become available.

## Real external solver execution

`resources/cae/external-solvers.edn` pins OpenFOAM v2506 to an ARM64 OCI image
digest. `clojure -M:dataset -m run-openfoam-evidence` copies the image's own
`icoFoam/cavity` tutorial, hashes every input dictionary, executes the real
`blockMesh` and `icoFoam` binaries, parses residual/Courant/continuity records,
hashes the logs and final fields, verifies the local image digest, and emits an
EDN evidence envelope. No shell command or unpinned image reference comes from
the case payload.

The committed run in `resources/cae/evidence` completed 100 time steps to
`t=0.5` with maximum Courant number 0.852134 and final cumulative continuity
error `-4.17776e-18`. This proves real process execution and traceability, not
general OpenFOAM solution accuracy; its qualification-matrix scope explicitly
excludes an accuracy or design-signoff claim.

The same evidence path now executes CalculiX 2.21 from a locally built ARM64
container whose Ubuntu base digest and `calculix-ccx=2.21-1` package version are
fixed in `containers/calculix/Dockerfile`. Run
`clojure -M:dataset -m run-calculix-evidence` to solve the committed C3D8 unit
cube, hash its INP/log/FRD/STA/CVG files, parse the actual FRD displacement
field, and compare maximum axial displacement with the closed-form 0.001 m
answer. The committed run has eight nodal samples and zero relative error for
that quantity. This narrowly verifies linear, small-strain axial response; it
does not qualify nonlinear contact, plasticity, large deformation, fracture,
or arbitrary industrial models.

`clojure -M:dataset -m run-mpi-evidence` verifies a real four-rank OpenMPI
4.1.6 runtime in a digest-pinned ARM64 container. The clean-room Kotoba worker
distributes one million midpoint-integration samples, performs real
`MPI_Allreduce` and `MPI_Gather` collectives, and emits one audit record per
rank. The runner executes it twice, requires byte-identical output hashes,
checks contiguous rank IDs and sample conservation, and rejects a π error above
`1e-12`. The committed run assigns 250,000 samples to every rank and has an
absolute error of about `1.10e-13`. This proves single-container multi-process
execution only; multi-node networking, scaling efficiency, failure recovery,
and production CFD/FEM decomposition remain outside the qualified scope.

`clojure -M:dataset -m run-calculix-contact-evidence` executes a separate 3D
geometrically nonlinear contact case. Two C3D8 blocks start with a gap; a
prescribed `-0.2` displacement closes it and compresses the pair using
surface-to-surface penalty contact. The evidence parser requires every one of
the 22 load increments to converge, a nonzero final contact set, compressive
normal force, completed NLGEOM output, and reaction/contact-force equilibrium.
The committed CalculiX 2.21 run activates 28 contact elements and reports
12,377.43 units of opposing contact force and top reaction, for a parsed
relative balance error of zero. This verifies this frictionless elastic block
case only; it does not validate friction, plasticity, self-contact, impact,
mesh convergence, or general production assemblies.

`clojure -M:dataset -m run-calculix-plastic-evidence` executes a material-
nonlinear C3D8 load/unload cycle with bilinear isotropic hardening. The
piecewise amplitude rises beyond yield and returns to zero; the parser joins 42
DAT history snapshots and requires every increment to converge, positive PEEQ
near mid-cycle, zero final axial load and stress, retained PEEQ, and nonzero
residual elongation. The committed run reaches `PEEQ=2.02765e-4`, unloads to an
axial force below `1e-15`, and retains `1.158657e-4` residual elongation. This
is a constrained uniaxial-strain material verification, not calibration for a
specific production alloy; cyclic hardening, anisotropy, damage, rate and
temperature dependence still require independent material data and validation.

`clojure -M:dataset -m run-calculix-mesh-study` generates and executes four
consistently refined C3D8 meshes for a 3D NLGEOM cantilever: 10, 80, 640 and
5,120 elements. It hashes every generated INP and solver output, requires all
real runs to complete, and evaluates rolling three-grid Richardson/GCI studies.
Adding the 6,561-node level changes the mean tip response from `-1.779019` to
`-1.824416`; the fine-side observed order rises from `1.3935` to `1.7751` and
GCI falls from `6.709%` to `1.2839%`, a `5.225x` reduction that passes the
declared 3% study target. This target applies only to this response and case;
it is not a universal mesh-independent or industrial-accuracy claim.

`clojure -M:dataset -m run-calculix-contact-mesh-study` executes three tilted
surface-to-surface contact meshes and deliberately separates global equilibrium
from local pressure qualification. All runs balance top reaction and integrated
contact force to the printed precision, but maximum CSTR is `1850.36`,
`1648.05`, then `1651.93`: the sequence is non-monotonic, so observed order and
GCI are undefined. The evidence run passes as a sensitivity audit while the
local-pressure scope fails closed as `:local-pressure-not-qualified`. A correct
total force must never be used to claim a converged local contact maximum.

`clojure -M:dataset -m run-calculix-plastic-mesh-study` applies a controlled
strain gradient to three bilinear-isotropic-hardening C3D8 meshes and parses
every final PEEQ integration-point value. Maximum PEEQ increases monotonically
from `0.0017421` through `0.0018330` to `0.0018788`; the observed order is
`0.9899` and fine-grid GCI is `3.0886%`, below the declared 5% target. This
qualifies maximum PEEQ only for this smooth gradient case. It does not cover
notches, singularities, damage, fracture, another geometry, or an uncalibrated
production material.

`clojure -M:dataset -m run-calculix-thermoplastic-evidence` executes a real
CalculiX 2.21 3D coupled temperature-displacement step with temperature-dependent
elastic/plastic tables, thermal expansion and conduction. The pinned run solves
an interior temperature between 293.15 K and 773.15 K, activates nonzero heat
flux and plastic strain, and converges all 22 increments. It proves execution of
the coupled kernels for this two-element reference case only; mesh convergence,
transient accuracy, experimental correlation and production material validity
remain excluded.

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

UI localization uses [`kotoba-lang/i18n`](https://github.com/kotoba-lang/i18n):
the English and Japanese EDN catalogs are embedded at CLJS compile time and
resolved at runtime through `i18n.core`. The toolbar locale selector updates
both Hiccup chrome and dynamic WebGPU scene/action labels without a rebuild,
and persists the choice in browser storage.
If WebGPU is unavailable, the CAE report remains readable and reports the
fallback state rather than pretending to render.
