(ns fukan.common.extraction.clojure.module
  "Clojure grounding for the code `Module` vocabulary — the FACT vocabulary (`Ns`), the extraction that
   builds it, and the design↔Clojure CORRESPONDENCE (`Module ↦ Ns`, the twin ROOT).

   `Ns` is the codomain: the Clojure realization of a Module — an extracted namespace owning its
   functions. A namespace maps to a Module 1-on-1, so `Ns` reads no clj-kondo detail beyond its name and
   members. The essential `(correspond [Module ?m Ns ?ns] …)` pairs a canvas Module with its `Ns` twin —
   a canvas short-name is a separator-agnostic dotted suffix of the code namespace
   (`infra-model` ← `fukan.infra.model`) — into the ambient `corresponds`, the ROOT every `Operation ↦
   Fn` pairing nests within. The generic `Module` structure lives in `fukan.common.vocab.code.module`."
  (:require [fukan.canvas.core.substrate :as sub]
            [fukan.canvas.core.structure :as s :refer [defstructure]]
            [fukan.cozo.query :as cq]
            [fukan.common.extraction.clojure.operation :refer [Fn]]
            [fukan.common.vocab.code.module :as module :refer [Module]]))

;; ── the FACT vocabulary: a Clojure namespace ─────────────────────────────────
(defstructure Ns
  "The Clojure realization of a Module — an EXTRACTED namespace, owning its functions via `:child`
   (a `contains` species). The fact-side root the design Module twins with by name."
  {:child [:* Fn]})

;; ── the correspondence: the ENTIRE Module ↔ Ns bridge (the twin ROOT) ────────
;; Matching is a flat identity query — a canvas short-name is a separator-agnostic dotted suffix of the
;; code namespace. Realization is the single-slot map `{:child :child}`: every design member is realized
;; by a same-role fact member. This pairing is the ROOT the Operation↦Fn correspondence nests within
;; (its match joins into the ambient `corresponds`). Coverage is a READING of the join, not a law.
(s/correspond [Module ?m Ns ?ns]
  [(named ?m ?mn) (named ?ns ?nn)
   [(name-match :qualified-suffix ?mn ?nn)]]
  {:child :child})


;; ── the ADOPTION frontier: the readings that make INCREMENTAL adoption navigable ──────────────
;; A project adopts fukan one module at a time, LEAF-UPWARD: `Operation :delegates` may only target an
;; authored Operation, so the adopted set is downward-closed under delegation BY CONSTRUCTION — a module
;; is adoptable only once everything it delegates to is already modelled. These readings serve that order.
;; They are READINGS, not laws: an unadopted namespace is not an offender, it is simply not yet claimed.

;; `adopted` names the claim itself — the Ns half of a live `Module ↦ Ns` pairing — so a coverage reading
;; can be relativized to the claimed region instead of asserting total coverage over the whole codebase.
;; Two consumers: `adopted-namespaces` below, and `unaccounted-public`
;; (fukan.common.extraction.clojure.operation), which reaches it BY NAME through datalog injection —
;; no require, hence no compile cycle back from the Operation fragment into this one.
(s/defrelation :adopted
  "Code namespace ?ns is CLAIMED by the model — the Ns half of a live `Module ↦ Ns` pairing."
  [?ns]
  [(is ?ns ::Ns) (corresponds ?_m ?ns)])

(defn- eid-names
  "eid → `:entity/name`, as one map. A single-attribute pull the readings below join against in
   Clojure rather than re-joining per clause in datalog (see `ns-dependencies` on why that matters)."
  [db]
  (into {} (cq/q '[:find ?e ?n :where [?e :entity/name ?n]] db)))

(defn ns-dependencies
  "The complete CODE-side namespace dependency graph as a set of [from to] name pairs — ?a owns a
   function that calls one ?b owns. A pure read over the extracted `:calls` graph: unlike
   `module-depends` (fukan.common.vocab.code.subsystem), which is built from authored `:delegates` and
   so says nothing until a region is modelled, this needs no authoring at all and is visible the moment
   extraction runs. That is what makes it the SCOUTING instrument — the leaves of an entirely unmodelled
   codebase are readable before a single line is authored. Intra-project only: extraction resolves
   `:calls` between extracted functions, so calls into libraries are not edges here (they surface as
   `:performs` effects instead).

   ⚠ Composed from two SINGLE-relation pulls joined in Clojure, not as one datalog conjunction, and that
   is not a style preference. Measured on clojure-mcp (72 namespaces, 659 functions, 742 call edges) the
   conjunction `(calls ?f ?g) (contains ?a ?f) (contains ?b ?g)` costs 58-69s where the two pulls cost
   0.70s and the Clojure join is instant — identical 154 edges, ~100x. Reversing clause order, dropping
   the sort guards, using the `:child` species instead of the `contains` genus, and writing raw `:rel/*`
   triples with no rule at all ALL land in the same 58-69s, so the cost is the query compiler's
   three-way join plan, not this relation's shape. It is also why there is no `ns-depends` defrelation:
   registering one would hand every future law a 60-second landmine."
  [db]
  (let [nm  (eid-names db)
        own (into {} (for [[a f] (cq/q '[:find ?a ?f :in $ % :where (contains ?a ?f)] db (s/vocab-rules))]
                       [f (nm a)]))]
    (set (for [[f g] (cq/q '[:find ?f ?g :in $ % :where (calls ?f ?g)] db (s/vocab-rules))
               :let  [a (own f) b (own g)]
               :when (and a b (not= a b))]
           [a b]))))

(defn adopted-namespaces
  "The names of the code namespaces the model has CLAIMED — the adopted region."
  [db]
  (let [nm (eid-names db)]
    (set (keep nm (cq/q '[:find [?ns ...] :in $ % :where (adopted ?ns)] db (s/vocab-rules))))))

(defn adoption-frontier
  "The FRONTIER: [adopted-ns unadopted-ns] name pairs where adopted code calls out into code the model
   does not yet claim.

   This is the blind spot leaf-upward adoption cannot see any other way. An adopted Operation whose code
   calls into an unadopted namespace carries NO `:delegates` edge — the slot may only target an authored
   Operation, so the edge is unauthorable — and no `realized-delegates` either, since that needs both
   ends paired. The model therefore asserts the operation delegates to nothing, and nothing contradicts
   it. This reading is that contradiction: both the check that a module you called a leaf really is one,
   and the ranked worklist of what to adopt next."
  [db]
  (let [ad (adopted-namespaces db)]
    (set (filter (fn [[a b]] (and (ad a) (not (ad b)))) (ns-dependencies db)))))

(defn adoption-candidates
  "Unadopted namespaces that are LEAVES — they depend on no other namespace in the project, so modelling
   one drags nothing else in — as [name fan-in] pairs, most depended-upon first.

   Fan-in is the ranking because adopting a high-fan-in leaf unblocks the most callers for the next step:
   this is where a leaf-upward adoption starts."
  [db]
  (let [nm     (eid-names db)
        all    (keep nm (cq/q '[:find [?ns ...] :in $ % :where (is ?ns ::Ns)] db (s/vocab-rules)))
        ad     (adopted-namespaces db)
        edges  (ns-dependencies db)
        out    (set (map first edges))
        in-deg (frequencies (map second edges))]
    (->> all
         (remove ad) (remove out)
         (map (fn [n] [n (get in-deg n 0)]))
         (sort-by (juxt (comp - second) first))
         vec)))

(defn extract-module
  "Build an extracted `Ns` InstanceValue named `mname` owning the functions named by `op-ids` (their
   natural-key ids) via `:child` — `substrate/Ref`s the assembler resolves to the `Fn` roots.
   Provenance (`:val/extracted`) is stamped by the BUILD at the merge (`substrate/stamp-stratum`), not here."
  [mname op-ids]
  (sub/->InstanceValue ::Ns (str mname) nil nil
                       [{:rk :child :card :many :targets (mapv sub/->Ref op-ids)}] false))
