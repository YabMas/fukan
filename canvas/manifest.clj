(ns canvas.manifest
  "The faculty build-MANIFEST — which code Module(s) build each subject faculty. Editorial: a map the
   system overview renders (faculty ⟶ realizing modules), NOT a drift-check. The genuine model↔code
   correspondence is the op-layer `fukan.target.correspondence/Realization` (authored Operation ⟷
   extracted Operation); this manifest names realizers that have NO extracted twin to check against —
   the faculties are abstract portraits — so it is a hand-authored map, verified only for the
   overview's sake. Authored OFF the pure subject domain: code is a projection of the model, so the
   subject never names its realizers; the manifest names them on its behalf."
  (:require [fukan.canvas.core.structure :refer [defstructure]]
            [lib.grouping :refer [Grouping]]
            ;; the subject faculties are PORTRAITS (grammar nodes); manifest entries name them by tag
            [canvas.subject :as subj]
            [canvas.architecture.kernel :as kernel]
            [canvas.architecture.canvas-source :as canvas-source]
            [canvas.architecture.target :as target]
            [canvas.architecture.probe-surface :as probe-surface]
            [canvas.architecture.materialize :as materialize]))

(defn ^:export realizes->tag
  "Manifest's authoring syntax (the `(syntax …)` hook, map → map): `:realizes` is authored as the
   realized faculty's structure SYMBOL (`subj/Model` / `subj/Source` / `subj/Lens` /
   `subj/Projection`); rewrite it to the qualified tag STRING (matching reflection's `:val/tag`), so
   the system overview can join the entry to the faculty's reflected grammar node by tag. The symbol
   resolves through its var (a structure's identity is its defining ns + name), so a typo throws at
   macro-expansion. A non-symbol passes through."
  [m]
  (if-let [s (:realizes m)]
    (if (symbol? s)
      (if-let [v (resolve s)]
        (let [mm (meta v)]
          (assoc m :realizes (str (keyword (str (ns-name (:ns mm))) (name (:name mm))))))
        (throw (ex-info (str "Manifest :realizes — unknown faculty " s) {:realizes s})))
      m)
    m))

(defstructure Manifest
  "An entry in the faculty build-manifest: a subject (design) concept and the code Module(s) that
   build it. The link lives HERE, on a dedicated node, precisely so it is element-AGNOSTIC: it names
   the realizing code element(s) in `:by` — a `Module` today; an `Operation`, a `Kind`, or any code
   element we invent tomorrow, with ZERO change to those structures. Embedding `:realizes` on `Module`
   would force the same slot onto `Operation`, `Kind`, … in turn; reifying the relation expresses
   'code realizes design' ONCE, for any code element. Crosses subject→code by QUALIFIED TAG — the
   faculties are portraits (grammar nodes), so the manifest names them, it does not edge to an
   instance.

   EDITORIAL, not a correspondence check: nothing extracted contradicts a manifest entry (the
   faculties have no code-up twin), so this is a hand-authored map the system overview renders — the
   genuine model↔code drift-check is the op-layer `fukan.target.correspondence/Realization`."
  {:realizes :string    ; the qualified tag of the realized faculty (authored as its symbol)
   :by       [:* Any]}  ; the realizing code element(s) — any element, not just Module
  (syntax realizes->tag))

;; one entry per use-side faculty; project (the Projection) IS materialization, the read faculty
;; (Lens) is built by the probe runner AND the correspondence checker, the in-fold (Source) by both
;; ingestion modules. :realizes names the faculty by tag (the syntax hook resolves the symbol).
(Manifest z-model      {:realizes subj/Model      :by [kernel/core-structure]})
(Manifest z-source     {:realizes subj/Source     :by [canvas-source/canvas-source target/target-clojure]})
(Manifest z-lens       {:realizes subj/Lens       :by [probe-surface/probes target/target-correspondence]})
(Manifest z-projection {:realizes subj/Projection :by [materialize/materialize]})

(Grouping faculty-manifest
  {:child [z-model z-source z-lens z-projection]})
