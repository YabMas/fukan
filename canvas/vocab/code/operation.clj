(ns canvas.vocab.code.operation
  "Code vocab — `Operation`: the unified computational unit, AUTHORED (a self-model's intent)
   or EXTRACTED from code (fact-stratum, stamped by the build), plus its model↔code correspondence.
   The three node-level demands (realized / type-coverage / covered) ride the `(corresponds …)`
   declaration on Operation — no separate law-holder defstructures. The drift/coverage/type-drift
   readers name the generated law keys directly. (The op pairing `op-twin` itself lives in `module`
   — it is built on the Module name bridge — and is referenced here via datalog injection; the
   `defn→Operation`+`:calls` extraction is added with the extractor.)"
  (:require [clojure.edn :as edn]
            [fukan.canvas.core.structure :as s :refer [defstructure]]
            [fukan.canvas.core.typing :as typing]
            [fukan.cozo.query :as cq]
            [canvas.vocab.type :as ct :refer [Schema]]
            [canvas.vocab.code.effect :refer [Effect]]
            [canvas.vocab.grouping :refer [Connected]]
            [canvas.vocab.code.kind :refer [Kind]]))

(defn ^:export signature->slots
  "Operation's authoring syntax. Existing map form remains valid:
   `{:signature [:=> INPUT OUTPUT] :performs [...]}`.

   The concise form makes the signature the primary body:
   `(Operation f [:=> INPUT OUTPUT] {:performs [...]})`.

   The signature is rewritten into the ordered+labelled `:in` vector and the `:out` entry.
   INPUT is `[:catn [:name Type] …]` (named params → ordered + labelled `[name Type]`
   pairs) or `[:cat]` (nullary). A `[:cat Type …]` (positional, unnamed) is REJECTED —
   name your parameters. The Types are ordinary malli, expanded by the Schema reader."
  [body]
  (let [m (cond
            (map? body) body

            (and (vector? body) (= :=> (first body)))
            {:signature body}

            (and (vector? body) (vector? (first body)) (= :=> (ffirst body)))
            (let [[signature opts] body]
              (when-not (or (nil? opts) (map? opts))
                (throw (ex-info (str "operation options after a positional signature must be a map: "
                                     (pr-str opts))
                                {:body body})))
              (assoc (or opts {}) :signature signature))

            :else
            (throw (ex-info (str "operation body must be a slots map or a signature form: "
                                 (pr-str body))
                            {:body body})))]
    (if-not (contains? m :signature)
      m
      (let [form (:signature m)]
      (when-not (and (vector? form) (= :=> (first form)) (= 3 (count form)))
        (throw (ex-info (str "signature must be a malli function schema [:=> INPUT OUTPUT]: " (pr-str form)) {:form form})))
      (let [[_ input output] form]
        ;; keep this guard ahead of catn->pairs: it reports against the whole [:=> …] form
        (when-not (vector? input)
          (throw (ex-info (str "signature input must be [:catn …] or [:cat]: " (pr-str input)) {:form form})))
        (let [in (ct/catn->pairs input)]
          (cond-> (-> m (dissoc :signature) (assoc :out output))
            (seq in) (assoc :in in))))))))

(defstructure Operation
  "A named unit of computation — the UNIFIED computational unit. An `Operation` is either
   AUTHORED (a self-model's intent: input/output Shapes, Effects, intended calls) or
   EXTRACTED from code (fact-stratum, stamped by the BUILD at the merge — name + privacy from
   the plug-point, and actual calls). A modelled Operation corresponds 1-on-1 (by name + corresponding Module)
   to its extracted twin; the two stay distinct nodes so spec and actual remain checkable.

   Authored with a malli signature: `(Operation f \"doc\" {:signature [:=> [:catn [:name Type] …] Out] :delegates […]})`
   — the `(syntax signature->slots)` hook rewrites `:signature` to the `:in`/`:out` entries.

   A boundary sketch authors `:delegates` (the cross-module surfaces it relies on — designed
   dependencies) and `:guidance` (implementer-directed intent); it does NOT author `:calls` —
   internal wiring is extraction's job. `:calls` is therefore the EXTRACTED actual-call graph.

   Corresponds NESTED (:by-name): a design Operation twins the same-named extracted one within twinned Modules."
  (includes Connected)
  (corresponds :by-name
    ;; ex-Realization — vacuity guard: ∃ extracted Operation (fires only when code is extracted)
    (realized {:desc "every authored operation is realized by an extracted operation of the same name in the corresponding module"})
    ;; ex-TypeCoverage — positive twin (a missing twin is `realized`'s offence); public surface only
    (realized {:key :type-coverage
               :desc "every public modelled operation's realizing code carries a type signature (:malli/schema)"
               :when '[(exposed ?x)]
               :require '[[?t :val/sig ?_s]]})
    ;; ex-Encapsulation — the exemption flags are VOCAB's (the kernel never names them)
    (covered  {:desc "every public extracted operation is covered by the model or deliberately exempt"
               :unless '[[?x :val/private true] [?x :val/export true] [?x :val/test-support true]]}))
  (syntax signature->slots)          ; {:signature [:=> [:catn …] Out]} authoring entry (vocab-owned)
  {:in        [:* Schema]            ; input shapes — positional, each labelled with its param name
   :out       [:? Schema]            ; output schema (authored ops declare one; extracted may not)
   :performs  [:* {:covered-from [:calls* :performs]} Effect]  ; side effects; :covered-from ⇒ every effect the twin REACHES is declared (ex-EffectCorrespondence; over-declaration is soft — effect-drift)
   :delegates [:* {:transitive true :realized-by :calls :altitude :container :faithful true} Operation]  ; designed dependencies; :transitive ⇒ delegates+; :realized-by/:faithful ⇒ generated CallRealization/Fidelity pair at module altitude
   :dispatches-to [:* Operation]     ; indirection: handler Operations this dispatch point routes to (authored intent — a design statement, not an extracted fact)
   :guidance  [:? :string]           ; implementer-directed design intent (algorithm/perf/library) — rendered by the projection
   :calls     [:* {:transitive true} Operation]  ; the ACTUAL call graph (extraction's actuals; not authored); :transitive ⇒ calls+ (reach-through-calls)
   :private   [:? :boolean]          ; public/internal — the module's surface (from extraction)
   :export    [:? :boolean]          ; intentionally public for MECHANISM (macro emission / dynamic dispatch); settled, not a coverage gap (from ^:export)
   :test-support [:? :boolean]       ; intentionally public for TEST-SUPPORT (test isolation / setup, never called from production); settled (from ^:test-support)
   ;; :extracted [:? :boolean] is IMPLIED by (corresponds …) — the kernel mints the provenance slot.
   ;; the code's REALIZED malli signature (a pr-str'd `[:=> …]` form), stamped by extraction
   ;; from `:malli/schema` metadata; authored Operations leave it empty and use :in/:out.
   :sig       [:? :string]}
  ;; AUTHORED-SIDE typing discipline — every PUBLIC authored Operation (on a Module's `:exposes`
  ;; surface) declares an OUTPUT TYPE. ABSOLUTE, no opt-out: every op returns SOMETHING with a type,
  ;; down to `:nil` (side-effecting) or `:any` (genuinely dynamic) — a missing `:out` is an undeclared
  ;; contract, never a legitimate abstention. OUTPUT, not full signature: a nullary op legitimately has
  ;; no `:in`, so the output type is the part every op has and the part the public contract turns on.
  ;; Rides Operation itself (the law is about its `:out` slot) — no separate holder. Self-scoped to
  ;; Operation; the datalog :when narrows to the public authored surface.
  (law "every public authored operation declares an output type"
    (has :out :when '[(authored ?x) (exposed ?x)])
    :key :signature-completeness))

;; `authored`/`extracted-op` — the paired "an Operation, {not,} extracted from code" guards the
;; correspondence laws/lenses (and the op pairings `op-twin`/`op-ext-twin`) each quantify over.
;; Derived UNARY membership rules injected into every law AND every `cq/q` (the vocab-index is
;; ambient — a `cq/q` need not pass them); non-recursive, so they pay no fixpoint.
(s/defrelation :authored
  "an AUTHORED Operation ?o — a self-model's intent (not extracted from code)"
  '[?o]
  '[[?o :structure/of :canvas.vocab.code.operation/Operation] (not [?o :val/extracted true])])

(s/defrelation :extracted-op
  "an EXTRACTED Operation ?o — a fact stamped from code (the dual of `authored`)"
  '[?o]
  '[[?o :structure/of :canvas.vocab.code.operation/Operation] [?o :val/extracted true]])

;; `exposed` — an Operation on SOME Module's public `:exposes` surface. The recurring
;; "public surface" predicate the signature/type demands and the precision reading each
;; quantify over; a named unary relation so the laws read at domain altitude instead of
;; re-inlining the `:exposes`/`:rel/to` EAV pair. Injected into every law and `cq/q`.
(s/defrelation :exposed
  "an Operation ?x on some Module's public :exposes surface"
  '[?x]
  '[[?xr :rel/kind :exposes] [?xr :rel/to ?x]])

;; ── model↔code correspondence (op altitude) ──────────────────────────────────
;; The three demands (realized / type-coverage / covered) are declared above as (corresponds …)
;; sub-forms on Operation; see the `corresponds` entry. No separate law-holder defstructures.

(defn drifted-operations
  "The AUTHORED operations with no extracted twin, as a set of names. Empty ⇔ the model is fully
   realized in code. Reads the generated realized demand (:corresponds/Operation.realized)."
  [db]
  (cq/violation-names db :corresponds/Operation.realized))

(defn uncovered-public-operations
  "The ENCAPSULATION worklist — PUBLIC extracted operations with no authored twin and no exemption flag.
   A public, non-exempt, unmodelled function is an UNDECLARED PUBLIC SURFACE: model it or make it private.
   Reads the generated covered demand (:corresponds/Operation.covered)."
  [db]
  (cq/violation-names db :corresponds/Operation.covered))

(defn operation-sig
  "Render the AUTHORED Operation at `op-eid` to a malli function-schema
   `[:=> [:cat <each :in schema>] <:out schema, or :nil if none>]`, each `:in`/`:out`
   Schema rendered via the type dialect (`typing/render-type`). The `:in` targets are
   ordered/positional — rendered in `:rel/order` order — so the adherence comparison
   checks argument order and arity."
  [db op-eid]
  (let [ins  (->> (cq/q '[:find ?ord ?to :in $ ?from
                          :where [?r :rel/from ?from] [?r :rel/kind :in] [?r :rel/to ?to] [?r :rel/order ?ord]]
                        db op-eid)
                  ;; ?ord arrives a native number (typed-q) — sort by true numeric order
                  (sort-by (fn [[ord _]] (long ord)))
                  (mapv (fn [[_ to]] (typing/render-type db to))))
        out  (ffirst (cq/q '[:find ?to :in $ ?from
                             :where [?r :rel/from ?from] [?r :rel/kind :out] [?r :rel/to ?to]]
                           db op-eid))]
    [:=> (into [:cat] ins) (if out (typing/render-type db out) :nil)]))

(defn type-drifted-operations
  "AUTHORED operations whose modelled type disagrees with the realizing function's declared
   `:malli/schema` — a type-drift signal (only checked where the code carries an annotation).
   Pairs each authored op with its extracted twin through the shared `op-twin` rule (same name,
   corresponding module via `in-module` — the SAME membership the laws use, so public ops attached
   via `:exposes` are seen, not just `:child`-attached ones), additionally requiring the twin
   carries a `:val/sig`; collects the authored Operation's name when its rendered type does NOT
   adhere to the twin's realized signature."
  [db]
  (->> (cq/q '[:find ?s ?sn ?o :in $
               :where (op-twin ?s ?o) [?s :entity/name ?sn] [?o :val/sig ?sig]]
             db)
       (filter (fn [[s _ o]]
                 (not (typing/type-adheres?
                        (operation-sig db s)
                        (edn/read-string (:val/sig (cq/entity db o)))))))
       (map second) set))

(defn undertyped-operations
  "The PRECISION worklist — PUBLIC modelled Operations whose declared signature still contains an `:any`
   (an under-typed parameter or result), as a set of names. Distinct from coverage (the signature is
   PRESENT but imprecise — `:any` is an honest-but-weak type); the NEXT layer of the type story. A READING,
   not a law: `:any` is a legitimate declaration, so under-typing is a worklist, not a violation. Empty ⇔
   every public op's signature is fully precise. Reads the rendered signature, flagging any reachable `:any`."
  [db]
  (->> (cq/q '[:find ?o ?on :in $
               :where (authored ?o) [?o :entity/name ?on] (exposed ?o)]
             db)
       (filter (fn [[oeid _]] (some #{:any} (tree-seq coll? seq (operation-sig db oeid)))))
       (map second) set))
