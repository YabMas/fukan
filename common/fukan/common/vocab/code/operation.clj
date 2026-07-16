(ns fukan.common.vocab.code.operation
  "Code vocab — the `Operation` element: a named unit of computation, AND its own model↔code
   correspondence (the fact-side slots, the twin, the drift demands, the call-realization readers).

   An Operation's IDENTITY is the authored intent alone — its input/output types, the effects it
   performs, and its designed dependencies. Its CORRESPONDENCE — how an authored Operation is paired
   with the extracted code twin, and what the code must realize/cover/adhere-to — is declared here
   too (via `(s/correspond Operation …)`), the complete story of the one element in one file. The
   only thing NOT here is implementer-directed prose, which rides the kernel `:guidance` annotation.

   The `:calls` readers correlate a canvas module and its code twin with the kernel `name-match`
   bridge strategy (`:qualified-suffix`, the same one Module declares) — a generic builtin, used only
   inside quoted datalog rules, so this namespace needs no dependency on Module (Module requires
   Operation, not the reverse). The twin pairs an authored Operation with its extracted code twin by
   name WITHIN twinned Modules."
  (:require [fukan.canvas.core.structure :as s :refer [defstructure]]
            [fukan.cozo.query :as cq]
            [fukan.cozo.law :as law]
            [fukan.common.typing.malli :as ct :refer [Schema]]
            [fukan.common.vocab.code.effect :refer [Effect]]))

(defstructure Operation
  "A named unit of computation, either AUTHORED (a self-model's intent) or EXTRACTED from code (the fact
   stratum, stamped by the build at the merge). The two are DISTINCT nodes: a design Operation
   corresponds 1-on-1 to its extracted twin by name within twinned Modules (`:by-name`, nested), so
   intended and actual structure stay checkable against each other.

   Authored with a malli signature — `(Operation f \"doc\" {:signature [:=> [:catn [:name Type] …] Out]
   :delegates […]})` — which the `signature->slots` sugar rewrites into the `:in`/`:out` slots. A design
   op authors `:delegates` (the cross-module surfaces it relies on), never `:calls`: internal wiring is
   extraction's job, so the actual call graph rides a fact-side slot the extractor fills."
  {:in        [:* Schema]                          ; input types — positional, ordered, each labelled with its param name
   :out       [:? Schema]                          ; output type
   :performs  [:* Effect]                          ; the effects it performs
   :delegates [:* {:transitive true} Operation]})  ; designed dependencies — direct callees; :transitive ⇒ the delegates+ closure

;; `:guidance` (implementer-directed intent) is deliberately NOT a slot — it rides the kernel's
;; per-instance annotation (a `:val/guidance` leaf on any instance, the read-dual of a docstring).

;; Authoring sugar — machinery, not identity, so it lives off the defstructure and registers against the
;; tag from outside; the kernel applies it (map → map) at instance-expansion, per the Syntax plug-point.
(defn ^:export signature->slots
  "Rewrite an Operation's `:signature` (a malli function-schema) into the `:in`/`:out` slots via the type
   dialect's `ct/arrow->in-out`: the input's named params become the ordered `:in` vector, the output
   becomes `:out`. A slots map without a `:signature` passes through unchanged."
  [m]
  (if-not (contains? m :signature)
    m
    (let [{:keys [in out]} (ct/arrow->in-out (:signature m))]
      (cond-> (-> m (dissoc :signature) (assoc :out out))
        (seq in) (assoc :in in)))))

(s/register-syntax! ::Operation signature->slots)

;; An Operation's PROVENANCE is not vocab: `(design ?o)` (authored) and `(fact ?o)` (extracted) are the
;; kernel's universal substrate rules (`fukan.canvas.core.rules`), ambient in every law and query — pair
;; them with the op-kind rule `(Operation ?o)` where op-ness matters.

;; ── the correspondence: the fact-side slots + the model↔code demands ──────────
;; Declared from OUTSIDE the defstructure so the identity above stays clean, but IN this file — the one
;; element, its whole story. `(s/correspond Operation …)` contributes the extracted fact-slots (:calls,
;; :private, …), the twin (`:by-name`, nested within twinned Modules), and the drift demands (generated
;; as laws at the stable keys :corresponds/Operation.*). The concept's defstructure mentions none of it.

(s/correspond Operation :by-name
  {:calls     [:* {:transitive true} Operation]  ; the ACTUAL call graph (extraction's actuals); :transitive ⇒ calls+
   :private   [:? :boolean]          ; public/internal — the module's surface (from extraction)
   :export    [:? :boolean]          ; intentionally public for MECHANISM (^:export)
   :test-support [:? :boolean]}      ; intentionally public for TEST-SUPPORT (^:test-support)
  ;; ex-Realization — vacuity-guarded: ∃ extracted Operation
  (realized {:desc "every authored operation is realized by an extracted operation of the same name in the corresponding module"})
  ;; ex-TypeCoverage — a modelled op's realizing code carries a :malli/schema
  (realized {:key :type-coverage
             :desc "every modelled operation's realizing code carries a type signature (:malli/schema)"
             :require '[[?tr :rel/from ?t] [?tr :rel/kind :out]]})   ; the twin declares an :out ⇔ it carries a fn-schema
  ;; ex-Encapsulation — the exemption flags are VOCAB's (the kernel never names them)
  (covered  {:desc "every public extracted operation is covered by the model or deliberately exempt"
             :unless '[[?x :val/private true] [?x :val/export true] [?x :val/test-support true]]})
  ;; GATED signature adherence (exact match): wherever the twin declares a signature (an :out), the
  ;; modelled signature must EXACTLY adhere. A missing sig is type-coverage's offence — hence the :when guard.
  (agrees   {:key :adheres :by :signature
             :desc "every modelled operation's realizing code signature exactly adheres to its modelled type"
             :when '[[?tr :rel/from ?t] [?tr :rel/kind :out]]})
  ;; relation demands ABOUT Operation's own identity relations (:delegates / :performs):
  (delegates {:realized-by :calls :faithful true :altitude :container})  ; cross-module :delegates realized by a :calls path (+ faithful reverse)
  (performs  {:covered-from [:calls* :performs]}))                       ; every effect the twin REACHES over :calls*·:performs is declared

;; the `:signature` comparator the `(agrees {:by :signature})` demand runs per twin pair: SYMMETRIC
;; STRUCTURAL adherence over the decomposed signatures. Both strata store :in/:out as Schema nodes
;; that content-DEDUP across the merge (a shared type is ONE node), so a design op and its twin adhere
;; iff their ordered :in target eids and :out target eid are IDENTICAL — node identity, no per-pair
;; render. The whole sig index is built in two queries and cached on db identity (the demand runs the
;; comparator over every twin pair, so rendering each was the check's single dominant cost).
(defonce ^:private sig-index-cache (atom nil))          ; [db {op-eid [ordered-in-eids out-eid]}]

(defn- signature-index
  "op-eid → [ordered-:in-target-eids :out-target-eid] for every Operation, in TWO queries, memoized
   on db identity so the per-twin-pair comparator is a map lookup."
  [db]
  (let [[cdb idx] @sig-index-cache]
    (if (identical? cdb db)
      idx
      (let [ins  (reduce (fn [m [op ord to]] (update m op (fnil conj []) [(long ord) to]))
                         {} (cq/q '[:find ?op ?ord ?to :where
                                    [?op :structure/of :fukan.common.vocab.code.operation/Operation]
                                    [?r :rel/from ?op] [?r :rel/kind :in] [?r :rel/order ?ord] [?r :rel/to ?to]] db))
            outs (into {} (cq/q '[:find ?op ?to :where
                                  [?op :structure/of :fukan.common.vocab.code.operation/Operation]
                                  [?r :rel/from ?op] [?r :rel/kind :out] [?r :rel/to ?to]] db))
            idx  (into {} (for [op (into (set (keys ins)) (keys outs))]
                            [op [(mapv second (sort-by first (get ins op []))) (get outs op)]]))]
        (reset! sig-index-cache [db idx])
        idx))))

(s/register-comparator! :signature
  (fn [db a b] (= (get (signature-index db) a) (get (signature-index db) b))))

;; op-twin — an alias of the generic kernel `twin` restricted to the Operation kind.
;; The pairing semantics live in the (corresponds …) declarations: Module = the bridged root,
;; Operation = nested by name within twinned Modules. This alias exists for the correspondence
;; laws/readers that reference op-twin by name; the actual rules are generated by the declaration registry (`terms-of`).
(s/defrelation :op-twin
  "an authored Operation ?a and its extracted code twin ?b — the generic kernel `twin`
   restricted to Operation kind. The pairing semantics live in the (corresponds …)
   declarations (Module = the bridged root, Operation = nested by name); this alias exists
   for the correspondence laws/readers that reference op-twin."
  '[?a ?b]
  '[[?a :structure/of :fukan.common.vocab.code.operation/Operation] (twin ?a ?b)])

;; ── model↔code CALL realization readers ──────────────────────────────────────
;; The enforced laws are GENERATED from the :delegates slot options above
;; {:realized-by :calls :altitude :container :faithful true}; the generated keys are
;; :corresponds/Operation.delegates-realized and .delegates-faithful. These are thin worklist wrappers
;; over law/violations-of, plus the `uncovered-calls`/`unrealized-dispatch` on-graph queries. They
;; correlate a canvas module and its code twin with the kernel `name-match` bridge strategy
;; (`:qualified-suffix`, same as Module's `(bridge …)`), used only inside quoted rules.

(def ^:private unrealized-dispatch-rules
  "Reachability over the EXTRACTED graph, on-graph. `op-ext-twin` pairs an authored op with its
   extracted code twin (same name + `:qualified-suffix`-matching modules). `ext-edge` is a `:calls` edge;
   `ext-reaches` is its transitive closure — a rule-calls-rule recursion the kernel now allows, negated
   under stratification. A `defmulti` is an ordinary Operation, so calls through it and its
   method-body calls are all `:calls`, and reachability needs no separate dispatch edge. The
   injected rules (`Operation`/`design`/`fact`/`in-module`) are ambient in any `cq/q`. `in-module` binds
   Kinds too, so op-ness is guarded here explicitly with `(Operation …)`."
  '[[(op-ext-twin ?a ?e)
     (Operation ?a) (design ?a) [?a :entity/name ?n] (in-module ?a ?am)
     (Operation ?e) (fact ?e) [?e :entity/name ?n] (in-module ?e ?em)
     [(name-match :qualified-suffix ?am ?em)]]
    [(ext-edge ?from ?to) (calls ?from ?to)]
    [(ext-reaches ?a ?b) (ext-edge ?a ?b)]
    [(ext-reaches ?a ?b) (ext-edge ?a ?mid) (ext-reaches ?mid ?b)]])

(defn unrealized-dispatch
  "Authored cross-module delegations NOT realized op-level by the actual code — the target is reached
   neither by a direct call nor multi-hop THROUGH the code's call graph. A set of authored source-op
   names; empty ⇔ every intended dependency is backed by a real (possibly multi-hop) call path.

   A QUERY, not a law (like `uncovered-calls`): reachability is on-graph datalog (`ext-reaches`, the
   transitive closure of `:calls`, negated under stratification) — no Clojure walk. An offender's
   delegation has BOTH endpoints twinned in code yet no realized path between them; a delegation whose
   source or target has no extracted twin is out of scope. Asserted empty by the regression suite."
  [db]
  (->> (cq/q '[:find ?on1 :in $ %
               :where (delegates ?o1 ?o2)
                      (design ?o1)
                      [?o1 :entity/name ?on1] (in-module ?o1 ?cm1)
                      (in-module ?o2 ?cm2) [(not= ?cm1 ?cm2)]
                      (op-ext-twin ?o1 ?e1) (op-ext-twin ?o2 ?e2)
                      (not (ext-reaches ?e1 ?e2))]
             db unrealized-dispatch-rules)
       (map first) set))

(defn unrealized-delegates
  "The authored source Operations whose cross-module delegation is NOT realized by any actual call
   between the corresponding modules, as a set of op names. Empty ⇔ every intended module dependency
   is backed by real code. Reads the generated demand (:corresponds/Operation.delegates-realized)."
  [db-arg]
  (law/violation-names db-arg :corresponds/Operation.delegates-realized))

(defn uncovered-calls
  "Fidelity worklist — the dual of `unrealized-delegates` (a QUERY, not a law): actual cross-module
   module-calls (over `:calls`) with no corresponding intended cross-module delegation (over
   `:delegates`, bridged by the `:qualified-suffix` name-match), as a set of [caller-module callee-module]
   code-module-name pairs. The couplings the design has not yet declared — a signal, not a violation."
  [db-arg]
  ;; `name-match` is a FILTER (both names bound), not a generator, so the not-join binds ?km1/?km2 to
  ;; the extracted (fact) code-module names itself before matching — the design→code correspondence is
  ;; a test, not a source of km bindings (they arrive from the outer call's in-module).
  (set (cq/q '[:find ?km1 ?km2 :in $
               :where (calls ?e1 ?e2)
                      (fact ?e1) (fact ?e2)
                      (in-module ?e1 ?km1) (in-module ?e2 ?km2) [(not= ?km1 ?km2)]
                      (not-join [?km1 ?km2]
                        (delegates ?o1 ?o2)
                        (design ?o1)
                        (in-module ?o1 ?cm1) (in-module ?o2 ?cm2) [(not= ?cm1 ?cm2)]
                        [?fm1 :structure/of :fukan.common.vocab.code.module/Module] (fact ?fm1) [?fm1 :entity/name ?km1]
                        [?fm2 :structure/of :fukan.common.vocab.code.module/Module] (fact ?fm2) [?fm2 :entity/name ?km2]
                        [(name-match :qualified-suffix ?cm1 ?km1)]
                        [(name-match :qualified-suffix ?cm2 ?km2)])]
             db-arg)))

(defn unfaithful-calls
  "The ENFORCED fidelity offenders — extracted caller Operations making an undeclared cross-module
   call between MODELLED faculties, as a set of op names. Empty ⇔ every modelled-faculty coupling in
   the code is declared as intent. The modelled-both-ends subset of `uncovered-calls`; reads the
   generated demand (:corresponds/Operation.delegates-faithful)."
  [db-arg]
  (law/violation-names db-arg :corresponds/Operation.delegates-faithful))
