(ns canvas.principles.parse-dont-validate
  "PRINCIPLE — Parse, don't validate (Alexis King, 2019).

   Trust in data is ESTABLISHED ONCE, at a declared boundary, by parsing raw input into a
   trusted representation whose type makes the invariant unforgettable; everything below the
   line consumes that representation and is TOTAL. Never check-and-pass-along (a boolean
   validator throws the knowledge away); never re-establish trust deep in the core (shotgun
   parsing).

   The bundle: `TrustBoundary` designates the trusted Kind + its declared parsers (with the
   parser↔`produces` cross-check law); `Totality` holds the core below the line total;
   `produces` derives who constructs which Kind from the type graph; the `Boundary` reading
   surfaces the judgment material — undeclared producers (reader handing held state along is
   fine; a second parse point is not) and validator-shaped ops (public, take the Kind, return
   boolean: parse, don't validate)."
  (:require [fukan.canvas.core.structure :as s :refer [defstructure]]
            [fukan.canvas.core.lens :refer [Projection]]
            [fukan.cozo.query :as cq]
            [canvas.vocab.code.kind :refer [Kind]]
            [canvas.vocab.code.operation :refer [Operation]]))

;; `produces` — the :out mirror of Totality's :in navigation: an authored Operation and the Kind
;; its output type NAMES. Direct refs only (a `ref` schema's `:names` edge); descent into wrapped
;; shapes ([:or K :nil]) grows under pressure. Consumers: the TrustBoundary parser cross-check law
;; and the Boundary reading.
(s/defrelation :produces
  "an authored Operation ?o whose :out schema is a ref naming Kind ?k"
  '[?o ?k]
  '[(authored ?o)
    [?or :rel/from ?o] [?or :rel/kind :out] [?or :rel/to ?sch]
    [?sch :val/kind "ref"] [?nr :rel/from ?sch] [?nr :rel/kind :names] [?nr :rel/to ?k]])

(defstructure TrustBoundary
  "Designates a parse-don't-validate TRUST BOUNDARY — the complete boundary story in one element.
   `:kind` points at the TRUSTED ARTIFACT (the parsed, trusted representation — e.g. fukan's
   StructureDb): the `Totality` law reads it and holds the core below the line total. `:parsed-by`
   declares the operations that ESTABLISH that trust — the parse points where raw input becomes the
   trusted representation. DECLARED, not derived: an op that merely happens to output the Kind (a
   reader handing held state along, e.g. a get-accessor) is not a parser; intent distinguishes them,
   and the cross-check law below keeps the declaration honest against the type structure (`produces`).
   At least one parser is mandatory (`[:+]`): trust with no declared source is an unfounded assumption."
  {:kind      Kind
   :parsed-by [:+ Operation]}
  (law "every declared parser produces the boundary kind (its :out names it)"
    :offenders '[?tb ?o]
    :where '[[?pr :rel/from ?tb] [?pr :rel/kind :parsed-by] [?pr :rel/to ?o]
             [?kr :rel/from ?tb] [?kr :rel/kind :kind] [?kr :rel/to ?k]
             (not-join [?o ?k] (produces ?o ?k))]))

(defstructure Totality
  "Law-holder for code-up TOTALITY (parse-don't-validate, at the trust line): a trusted-core reader —
   a modelled Operation whose :in references a declared TrustBoundary Kind — operates on already-trusted
   data, so it must be TOTAL. An offender is such a reader whose extracted twin (op-twin) performs
   :throws. `:scope :global`; vacuous when no TrustBoundary is declared. Generic: the trust boundary is
   a parameter (a project declares its own), not a hardcoded StructureDb."
  (law "every trusted-core reader (its :in is a declared TrustBoundary) is total — its code performs no :throws"
    :scope :global
    :offenders '[?o]
    :where '[[?tb :structure/of ::TrustBoundary] [?tbr :rel/from ?tb] [?tbr :rel/kind :kind] [?tbr :rel/to ?k]
             (authored ?o)
             [?ir :rel/from ?o] [?ir :rel/kind :in] [?ir :rel/to ?sch]
             [?sch :val/kind "ref"] [?nr :rel/from ?sch] [?nr :rel/kind :names] [?nr :rel/to ?k]
             (op-twin ?o ?e)
             [?pr :rel/from ?e] [?pr :rel/kind :performs] [?pr :rel/to ?eff] [?eff :val/name "throws"]]))

(defn totality-violations
  "The ENFORCED TOTALITY offenders — trusted-core reader Operations (their :in references a declared
   TrustBoundary) whose realizing code is PARTIAL, as a set of op names. Empty ⇔ the modelled trusted
   core is total. Reads the single source of truth (the registered `Totality` law)."
  [db]
  (let [desc (-> (s/structure-by-tag ::Totality) :laws first :desc)]
    (->> (s/check db)
         (filter #(= desc (:law %)))
         (mapcat :offenders) (map first)
         (map #(:entity/name (cq/entity db %)))
         set)))

;; the principle's JUDGMENT surface — rendered by materialize/render-finding "Boundary"
(Projection Boundary
  "The trust story per boundary — declared parsers and their failure channels, undeclared
   producers, validator-shaped ops (a reading)."
  {:select '[(TrustBoundary ?n)]})
