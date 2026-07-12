(ns cae.sbse-block-mesh
  "Body-fitted multi-block Hex mesh for the NASA SBSE ideal test section."
  (:require [cae.sbse-geometry :as sbse]
            [clojure.string :as str]))

(defn- join-lines [xs] (str/join "\n" xs))
(defn- face [ids] (str "        (" (str/join " " ids) ")"))

(defn block-mesh-dict
  [{:keys [nx ny nz x-min-m x-max-m half-width-m top-m wall-normal-grading]
    :or {nx 24 ny 24 nz 12 x-min-m -0.9144 x-max-m 1.8288
         half-width-m 0.4572 top-m 0.9144 wall-normal-grading 20.0}}]
  (when-not (and (pos-int? nx) (pos-int? ny) (pos-int? nz)
                 (< x-min-m x-max-m) (pos? half-width-m) (pos? top-m)
                 (> wall-normal-grading 1.0))
    (throw (ex-info "valid positive SBSE block mesh controls are required" {})))
  (let [gx (inc nx) gz (inc nz)
        id (fn [level i j] (+ (* level gx gz) (* i gz) j))
        x-at (fn [i] (+ x-min-m (* i (/ (- x-max-m x-min-m) nx))))
        z-at (fn [j] (+ (- half-width-m) (* j (/ (* 2.0 half-width-m) nz))))
        vertices (for [level (range 2) i (range gx) j (range gz)
                       :let [x (x-at i) z (z-at j)
                             y (if (zero? level) (sbse/height-m x z) top-m)]]
                   (format "    (%.12f %.12f %.12f) // %d" x y z (id level i j)))
        blocks (for [i (range nx) j (range nz)
                     :let [b00 (id 0 i j) b10 (id 0 (inc i) j)
                           b11 (id 0 (inc i) (inc j)) b01 (id 0 i (inc j))
                           t00 (id 1 i j) t10 (id 1 (inc i) j)
                           t11 (id 1 (inc i) (inc j)) t01 (id 1 i (inc j))]]
                 (format "    hex (%d %d %d %d %d %d %d %d) (1 1 %d) simpleGrading (1 1 %.8f)"
                         b00 b01 b11 b10 t00 t01 t11 t10 ny wall-normal-grading))
        floor (for [i (range nx) j (range nz)]
                (face [(id 0 i j) (id 0 i (inc j)) (id 0 (inc i) (inc j)) (id 0 (inc i) j)]))
        ceiling (for [i (range nx) j (range nz)]
                  (face [(id 1 i j) (id 1 (inc i) j) (id 1 (inc i) (inc j)) (id 1 i (inc j))]))
        inlet (for [j (range nz)]
                (face [(id 0 0 j) (id 1 0 j) (id 1 0 (inc j)) (id 0 0 (inc j))]))
        outlet (for [j (range nz)]
                 (face [(id 0 nx j) (id 0 nx (inc j)) (id 1 nx (inc j)) (id 1 nx j)]))
        side-a (for [i (range nx)]
                 (face [(id 0 i 0) (id 0 (inc i) 0) (id 1 (inc i) 0) (id 1 i 0)]))
        side-b (for [i (range nx)]
                 (face [(id 0 i nz) (id 1 i nz) (id 1 (inc i) nz) (id 0 (inc i) nz)]))
        patch (fn [name type faces]
                (str "    " name "\n    {\n        type " type ";\n        faces\n        (\n"
                     (join-lines faces) "\n        );\n    }"))]
    {:format :sbse-block-mesh-v1 :nx nx :ny ny :nz nz
     :cells (* nx ny nz) :blocks (* nx nz) :vertices (* 2 gx gz)
     :dict (str "FoamFile\n{\n    format ascii;\n    class dictionary;\n    object blockMeshDict;\n}\n"
                "scale 1;\nvertices\n(\n" (join-lines vertices) "\n);\nblocks\n(\n"
                (join-lines blocks) "\n);\nedges ();\nboundary\n(\n"
                (str/join "\n" [(patch "inlet" "patch" inlet)
                                  (patch "outlet" "patch" outlet)
                                  (patch "floorBump" "wall" floor)
                                  (patch "ceiling" "wall" ceiling)
                                  (patch "sideA" "wall" side-a)
                                  (patch "sideB" "wall" side-b)])
                "\n);\nmergePatchPairs ();\n")}))
