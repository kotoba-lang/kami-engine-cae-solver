# cae-solver-clj

[![CI](https://github.com/kotoba-lang/cae-solver/actions/workflows/ci.yml/badge.svg)](https://github.com/kotoba-lang/cae-solver/actions/workflows/ci.yml)

The CAE solver **contract**: one `solve` multimethod dispatching on `[:solver :kind]`, so a case is answered by a reduced-order backend today and a high-fidelity one (kami-cfd `:lbm`) tomorrow with no caller change. **Purpose: solver dispatch.**

Part of the clean-sheet vehicle-design / CAE stack (purpose-split shared libs).
Zero-dep portable `.cljc`. Run `clojure -M:test`.
