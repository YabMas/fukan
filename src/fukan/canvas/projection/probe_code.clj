(ns fukan.canvas.projection.probe-code
  "Projector for the executable agent: reads the agent model (the structure db) and emits,
   per probe, the artifacts needed to run it. Pure (db → forms/strings); persisting to src/
   + build-wiring is a later cut.
   - A COMPOSING probe (it `:calls` a modelled capability) → a mechanical probe fn-form plus
     a contract predicate (list-of-Str findings).
   - A FRESH probe (no `:calls`) → a projected Instruction (assembled from modelled facts) for
     an implementing LLM to write the leaf, plus the same contract predicate."
  (:require [datascript.core :as d]))

;; Model→Code bridges (cut-1 minimal; generalize by deriving from the model later).
(def ^:private capability->var
  "Modelled capability name → the real var the projected code calls."
  {"check" 'fukan.canvas.core.structure/check})

(def ^:private kind->pred
  "Shape leaf Kind → the predicate the projected contract uses."
  {"Str" 'clojure.core/string?})

(def ^:private kind->desc
  "Shape leaf Kind → a human noun for the projected Instruction."
  {"Str" "strings"})

(defn- probe-lens
  "The name of the lens a probe reads through."
  [db probe-name]
  (ffirst (d/q '[:find ?ln :in $ ?pn
                 :where [?p :entity/name ?pn] [?p :structure/of :Probe]
                        [?r :rel/from ?p] [?r :rel/kind :through] [?r :rel/to ?l]
                        [?l :structure/of :Lens] [?l :entity/name ?ln]]
               db probe-name)))

(defn- probe-gating
  "Whether the probe's yielded finding gates (a trust Signal)."
  [db probe-name]
  (ffirst (d/q '[:find ?g :in $ ?pn
                 :where [?p :entity/name ?pn] [?p :structure/of :Probe]
                        [?r :rel/from ?p] [?r :rel/kind :yields] [?r :rel/to ?f]
                        [?f :structure/of :Finding] [?f :val/gating ?g]]
               db probe-name)))

(defn- probe-capability
  "The modelled capability name a composing probe :calls (or nil for a fresh probe).
   Cut 1 supports a single :calls edge."
  [db probe-name]
  (let [results (d/q '[:find ?cn :in $ ?pn
                       :where [?p :entity/name ?pn] [?p :structure/of :Probe]
                              [?r :rel/from ?p] [?r :rel/kind :calls] [?r :rel/to ?c]
                              [?c :structure/of :Stage] [?c :entity/name ?cn]]
                     db probe-name)]
    (when (> (count results) 1)
      (throw (ex-info "cut-1 projector handles a single :calls edge only"
                      {:probe probe-name :capabilities (mapv first results)})))
    (ffirst results)))

(defn- finding-shape
  "The probe's finding shape value node, or nil."
  [db probe-name]
  (ffirst (d/q '[:find ?sh :in $ ?pn
                 :where [?p :entity/name ?pn] [?p :structure/of :Probe]
                        [?ry :rel/from ?p] [?ry :rel/kind :yields] [?ry :rel/to ?f]
                        [?rs :rel/from ?f] [?rs :rel/kind :shape] [?rs :rel/to ?sh]]
               db probe-name)))

(defn- list-of-kind
  "If `shape-eid` is a list-of-<Kind> shape, return that Kind's name; else nil.
   (Cut 1 handles exactly the list-of-leaf shape.)"
  [db shape-eid]
  (when (= "list" (:val/kind (d/entity db shape-eid)))
    (ffirst (d/q '[:find ?kn :in $ ?sh
                   :where [?of :rel/from ?sh] [?of :rel/kind :of] [?of :rel/to ?el]
                          [?ty :rel/from ?el] [?ty :rel/kind :type] [?ty :rel/to ?k]
                          [?k :entity/name ?kn]]
                 db shape-eid))))

(defn- contract-form
  "A predicate over a probe RESULT: its :finding is a list whose elements satisfy the
   shape leaf's predicate. Cut 1: the finding shape must be list-of-<Kind>."
  [db probe-name]
  (let [sh   (finding-shape db probe-name)
        kind (some->> sh (list-of-kind db))
        pred (kind->pred kind)]
    (when-not pred
      (throw (ex-info (str "cut-1 contract handles list-of-leaf findings only; got shape for "
                           (pr-str probe-name))
                      {:probe probe-name :kind kind})))
    (list 'fn '[result]
          (list 'and
                (list 'sequential? '(:finding result))
                (list 'every? pred '(:finding result))))))

(defn- probe-focus
  "The focus prose of the lens a probe reads through."
  [db probe-name]
  (ffirst (d/q '[:find ?fc :in $ ?pn
                 :where [?p :entity/name ?pn] [?p :structure/of :Probe]
                        [?r :rel/from ?p] [?r :rel/kind :through] [?r :rel/to ?l]
                        [?l :structure/of :Lens] [?l :val/focus ?fc]]
               db probe-name)))

(defn- finding-holds
  "The holds invariant prose of the probe's yielded finding (or nil)."
  [db probe-name]
  (ffirst (d/q '[:find ?h :in $ ?pn
                 :where [?p :entity/name ?pn] [?p :structure/of :Probe]
                        [?r :rel/from ?p] [?r :rel/kind :yields] [?r :rel/to ?f]
                        [?f :structure/of :Finding] [?f :val/holds ?h]]
               db probe-name)))

(defn- shape->desc
  "A human description of the finding shape (cut-2: list-of-<Kind>)."
  [db probe-name]
  (let [k (some->> (finding-shape db probe-name) (list-of-kind db))]
    (when-not (kind->desc k)
      (throw (ex-info "cut-2 instruction: list-of-leaf shapes only"
                      {:probe probe-name :kind k})))
    (str "a list of " (kind->desc k))))

(defn- instruction
  "A projected Instruction (instruct ⊂ projection) for an implementing LLM: assembled
   PURELY from modelled facts — the LLM writes only the leaf (the :finding computation)."
  [db probe-name]
  (let [lens  (probe-lens db probe-name)
        focus (probe-focus db probe-name)
        gate  (probe-gating db probe-name)
        sdesc (shape->desc db probe-name)
        holds (finding-holds db probe-name)]
    (str "Implement the fukan probe `probe-" probe-name "`.\n\n"
         "Focus: " focus "\n"
         "Signature: (probe-" probe-name " [target-db]) where target-db is a datascript model db.\n"
         "Return exactly the map {:lens \"" lens "\", :gating " gate ", :finding <result>}, "
         "where <result> is " sdesc ".\n"
         "Invariant (holds): " holds)))

(defn project-probe
  "Project the named probe.
   Composing probe (it :calls a modelled capability) → {:fn-form <probe fn> :contract-form <pred>}
   (mechanical, Cut 1).
   Fresh probe (no :calls) → {:instruction <projected spec> :contract-form <pred>}: a spec for an
   implementing LLM to write the leaf, plus the contract that gates the result."
  [db probe-name]
  (let [cap (probe-capability db probe-name)]
    (if cap
      (let [cap-var (capability->var cap)]
        (when-not cap-var
          (throw (ex-info (str "no known capability var for " (pr-str cap))
                          {:probe probe-name :capability cap})))
        {:fn-form (list 'fn '[target-db]
                        {:lens (probe-lens db probe-name)
                         :gating (probe-gating db probe-name)
                         :finding (list 'mapv 'str (list cap-var 'target-db))})
         :contract-form (contract-form db probe-name)})
      {:instruction (instruction db probe-name)
       :contract-form (contract-form db probe-name)})))
