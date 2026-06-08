(ns fukan.target.correspondence
  "The model↔code CORRESPONDENCE concern — deliberately separate from BOTH the
   abstract modelling domain (canvas/, e.g. `Operation`) and the code-structure domain
   (`Operation`). A domain definition's laws should concern only that domain's own
   behaviour and constraints; HOW the model is realized in code is an orthogonal
   question. Keeping it here means each can be reasoned about — and evolved — in
   isolation: focus on the domain without implementation noise, and focus on the
   implementation/correspondence question without touching the domains.

   It holds fukan's self-correspondence. fukan's projection convention is that an
   op-layer `Operation` named X in a canvas module is realized by a function named X in
   the CORRESPONDING code module — so the law matches on name AND module placement
   across the altitude gap between authored Operations (canvas/) and extracted Operations
   (src/), no `realizes` relation needed. Authored and extracted operations are both the
   one `:Operation` tag now (provenance `:extracted`, not tag, distinguishes them); the law
   references it only as data (a structure tag resolved at check-time over the merged graph), so this
   concern takes no code dependency on either domain."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [datascript.core :as d]
            [fukan.canvas.core.structure :as s :refer [defstructure]]
            [fukan.model.typing :as typing]))

(defn ^:export module-corresponds?
  "True when code namespace `km` realizes canvas module `cm`. Deterministic, separator-agnostic:
   split both on `[-.]` into segments; the canvas name's segments must be a SUFFIX of the code
   namespace's. So `infra-model` ← `fukan.infra.model`, `canvas-source` ←
   `fukan.canvas.projection.canvas-source`, `core-structure` ← `fukan.canvas.core.structure`.
   (Canvas module names are hyphenated and equal their vars; the code path is dotted — this rule
   bridges the two without the model authoring a second name string.)"
  [cm km]
  (let [segs #(str/split % #"[-.]")
        c    (segs cm)]
    (= c (take-last (count c) (segs km)))))

(defstructure Realization
  "A law-holder for the model↔code correspondence — it has no instances of its own;
   it exists to carry the cross-layer assertion in its own concern.

   `:scope :global` opts out of the default self-scoping (its offenders are Operations,
   not Realizations). The leading extracted-Operation clause is the real guard: the law
   is vacuous when no code is extracted — correspondence is only assertable when both
   layers share the graph — so registering it never disturbs a model-only `check`.

   AUTHORED and EXTRACTED operations are both `Operation`s now — provenance (`:extracted`),
   not tag, distinguishes them. The match is on name AND module: an authored Operation in
   canvas module C is realized only by an extracted Operation of the same name whose code
   module corresponds to C (module-corresponds?)."
  ;; Reads over the vocab-derived rules (check injects them) — domain altitude:
  ;; `(Operation …)`, `(named …)`, `(in-module …)`; `:val/extracted` splits the two sides.
  (law "every authored operation is realized by an extracted operation of the same name in the corresponding module"
    :scope :global
    :offenders '[?s]
    :where '[(Operation ?x) [?x :val/extracted true]                  ; guard: some code is extracted
             (Operation ?s) (not [?s :val/extracted true])            ; an authored operation
             (named ?s ?n) (in-module ?s ?cmn)
             (not-join [?n ?cmn]
               (Operation ?o) [?o :val/extracted true]
               (named ?o ?n) (in-module ?o ?kmn)
               [(fukan.target.correspondence/module-corresponds? ?cmn ?kmn)])]))

(defn drifted-operations
  "The AUTHORED operations in `db` with no same-named extracted operation, as a set of
   names. Empty ⇔ the model is fully realized in code. The focusable surface of the
   correspondence concern; reads the single source of truth (the registered law)."
  [db]
  (let [desc (-> (s/structure-by-tag :Realization) :laws first :desc)]
    (->> (s/check db)
         (filter #(= desc (:law %)))
         (mapcat :offenders) (map first)
         (map #(:entity/name (d/entity db %)))
         set)))

(defn operation-sig
  "Render the AUTHORED Operation at `op-eid` to a malli function-schema
   `[:=> [:cat <each :in schema>] <:out schema, or :nil if none>]`, each `:in`/`:out`
   Schema rendered via the type dialect (`typing/render-type`). The `:in` targets are
   collected in any order — the adherence comparison treats inputs as a set."
  [db op-eid]
  (let [ins  (->> (d/q '[:find ?to :in $ ?from
                         :where [?r :rel/from ?from] [?r :rel/kind :in] [?r :rel/to ?to]]
                       db op-eid)
                  (mapv (fn [[to]] (typing/render-type db to))))
        out  (ffirst (d/q '[:find ?to :in $ ?from
                            :where [?r :rel/from ?from] [?r :rel/kind :out] [?r :rel/to ?to]]
                          db op-eid))]
    [:=> (into [:cat] ins) (if out (typing/render-type db out) :nil)]))

(defn type-drifted-operations
  "AUTHORED operations whose modelled type disagrees with the realizing function's declared
   `:malli/schema` — a type-drift signal (only checked where the code carries an annotation).
   Mirrors `uncovered-operations`' authored↔extracted pairing (same name, corresponding
   module via `module-corresponds?`), additionally requiring the extracted twin carries a
   `:val/sig`; collects the authored Operation's name when its rendered type does NOT adhere
   to the twin's realized signature."
  [db]
  (->> (d/q '[:find ?s ?sn ?o
              :where [?s :structure/of :Operation] (not [?s :val/extracted true]) [?s :entity/name ?sn]
                     [?cr :rel/kind :child] [?cr :rel/from ?cm] [?cr :rel/to ?s]
                     [?cm :entity/name ?cmn]
                     [?o :structure/of :Operation] [?o :val/extracted true] [?o :entity/name ?sn]
                     [?o :val/sig _]
                     [?kr :rel/kind :child] [?kr :rel/from ?km] [?kr :rel/to ?o]
                     [?km :entity/name ?kmn]
                     [(fukan.target.correspondence/module-corresponds? ?cmn ?kmn)]]
            db)
       (filter (fn [[s _ o]]
                 (not (typing/type-adheres?
                        (operation-sig db s)
                        (edn/read-string (:val/sig (d/entity db o)))))))
       (map second) set))

(defn uncovered-operations
  "The DUAL of drifted-operations — EXTRACTED operations in `db` with no authored operation
   of the same name in a corresponding module, as a set of names: code not covered by the
   model. A query, not a law — unmodelled code is a coverage signal, not a violation (you
   don't model every function)."
  [db]
  (->> (d/q '[:find ?on
              :where [?o :structure/of :Operation] [?o :val/extracted true] [?o :entity/name ?on]
                     [?kr :rel/kind :child] [?kr :rel/from ?km] [?kr :rel/to ?o]
                     [?km :entity/name ?kmn]
                     (not-join [?on ?kmn]
                       [?s :structure/of :Operation] (not [?s :val/extracted true]) [?s :entity/name ?on]
                       [?cr :rel/kind :child] [?cr :rel/from ?cm] [?cr :rel/to ?s]
                       [?cm :entity/name ?cmn]
                       [(fukan.target.correspondence/module-corresponds? ?cmn ?kmn)])]
            db)
       (map first) set))
