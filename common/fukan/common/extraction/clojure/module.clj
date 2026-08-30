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
  {:child [:* Fn]}

  ;; ── the correspondence's TEETH, at the ROOT altitude ────────────────────────────────────────
  ;; The law rides `Ns` because `correspond` has no law position of its own: it lowers EXCLUSIVELY
  ;; to rules — definitional and conservative, which is THE TEST's verdict on it — so a denial
  ;; ABOUT a correspondence has to ride some structure, and the codomain declared beside it is the
  ;; honest one. If these accumulate, a law position on `correspond` is the change to make; two
  ;; laws across two files is not yet a pattern.
  (law "every modelled Module is realized by a namespace"
    ;; Without this, a Module that fails to pair is INVISIBLE. The Operation-level law
    ;; (fukan.common.extraction.clojure.operation) is scoped to modules that DID pair, so an
    ;; unpaired module takes everything inside it out of view along with itself. Measured on
    ;; nido: rename `nido.platform.charm-patch` to anything else and the operation law reports
    ;; nothing at all, while this one names the module. That scoping is deliberate — one cause,
    ;; one finding, at the altitude the cause lives at — and this law is what makes it safe.
    ;;
    ;; GATED on a namespace existing, for the reason Band's coverage law is gated on a band: a
    ;; build with no code root extracts nothing, and an ungated law would then report every
    ;; Module in the project. That is not a finding, it is the checker describing its own inputs.
    {:scope     :global
     :key       :correspondence/module-unrealized
     :offenders [?m]
     :rules     [[(some-ns ?ns) (is ?ns ::Ns)]]
     :where     [(some-ns ?_ns)
                 (is ?m Module) (design ?m)
                 (not-join [?m] (corresponds ?m ?ns))]}))

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
;; Two consumers: `adopted-namespaces` below, and the public-unaccounted LAW
;; (fukan.common.extraction.clojure.operation), which reaches it BY NAME through datalog injection —
;; no require, hence no compile cycle back from the Operation fragment into this one.
(s/defrelation :adopted
  "Code namespace ?ns is CLAIMED by the model — the Ns half of a live `Module ↦ Ns` pairing."
  [?ns]
  [(is ?ns ::Ns) (corresponds ?_m ?ns)])

(s/defrelation :ns-depends
  "Namespace ?a depends on ?b — ?a owns a function that calls one ?b owns (the extracted `:calls`
   graph at namespace altitude). Intra-project only: extraction resolves `:calls` between extracted
   functions, so calls into libraries are not edges here (they surface as `:performs` effects).

   Its design-side counterpart `module-depends` (fukan.common.vocab.code.subsystem) is built from
   authored `:delegates` and so says nothing until a region is modelled; this one needs no authoring
   at all and is visible the moment extraction runs. That is what makes it the SCOUTING instrument —
   the leaves of an entirely unmodelled codebase are readable before a line is authored."
  [?a ?b]
  [(is ?a ::Ns) (contains ?a ?f) (calls ?f ?g) (contains ?b ?g) (is ?b ::Ns) [(not= ?a ?b)]])

(defn ns-dependencies
  "The complete CODE-side namespace dependency graph as a set of [from to] name pairs."
  [db]
  (set (cq/q '[:find ?an ?bn :in $ %
               :where (ns-depends ?a ?b) [?a :entity/name ?an] [?b :entity/name ?bn]]
             db (s/vocab-rules))))

(defn adopted-namespaces
  "The names of the code namespaces the model has CLAIMED — the adopted region."
  [db]
  (set (cq/q '[:find [?n ...] :in $ %
               :where (adopted ?ns) [?ns :entity/name ?n]]
             db (s/vocab-rules))))

(defn adoption-frontier
  "The FRONTIER: [adopted-ns unadopted-ns] name pairs where adopted code calls out into code the
   model does not yet claim.

   This is the blind spot leaf-upward adoption cannot see any other way. An adopted Operation whose
   code calls into an unadopted namespace carries NO `:delegates` edge — the slot may only target an
   authored Operation, so the edge is unauthorable — and no `realized-delegates` either, since that
   needs both ends paired. The model therefore asserts the operation delegates to nothing, and
   nothing contradicts it. This reading is that contradiction: both the check that a module you
   called a leaf really is one, and the ranked worklist of what to adopt next."
  [db]
  (set (cq/q '[:find ?an ?bn :in $ %
               :where (ns-depends ?a ?b) (adopted ?a) (not (adopted ?b))
                      [?a :entity/name ?an] [?b :entity/name ?bn]]
             db (s/vocab-rules))))

(defn adoption-candidates
  "Unadopted namespaces that are LEAVES — they depend on no other namespace in the project, so
   modelling one drags nothing else in — as [name fan-in] pairs, most depended-upon first.

   Fan-in is the ranking because adopting a high-fan-in leaf unblocks the most callers for the next
   step: this is where a leaf-upward adoption starts. The leaf PREDICATE is datalog (a negated
   `ns-depends`); only the ordering is Clojure, over the edge set datalog already returned."
  [db]
  (let [in-deg (frequencies (map second (ns-dependencies db)))]
    (->> (cq/q '[:find [?n ...] :in $ %
                 :where (is ?ns ::Ns) (not (adopted ?ns))
                        (not-join [?ns] (ns-depends ?ns ?_b))
                        [?ns :entity/name ?n]]
               db (s/vocab-rules))
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
