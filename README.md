# cae-solver-clj

The CAE solver **contract**: one `solve` multimethod dispatching on `[:solver :kind]`, so a case is answered by a reduced-order backend today and a high-fidelity one (kami-cfd `:lbm`) tomorrow with no caller change. **Purpose: solver dispatch.**

Part of the clean-sheet vehicle-design / CAE stack (purpose-split shared libs).
Zero-dep portable `.cljc`. Run `clojure -M:test`.
