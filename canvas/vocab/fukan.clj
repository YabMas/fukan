(ns canvas.vocab.fukan
  "TEMPORARY holding pen for `Totality` — the fukan-specific trusted-core totality law (its `:in`
   binds to fukan's own `StructureDb` trust artifact). Kept whole here until its own session lifts the
   general shape to the code vocab and the binding to fukan's design. The op pairing `op-twin` lives in
   `canvas.vocab.code.module`, referenced here via datalog injection. (`Coverage` was lifted to
   `fukan.canvas.core.coverage` on 2026-06-26.)"
  (:require [fukan.canvas.core.structure :as s :refer [defstructure]]
            [fukan.cozo.query :as cq]))

(defstructure Totality
  "Law-holder for code-up TOTALITY — the ENFORCED dual of the partiality worklist, at the TRUST LINE
   (parse-don't-validate). A trusted-core READER is a modelled Operation whose `:in` signature
   references the trust artifact `StructureDb` (the Model) — it operates ON the trusted graph.
   Parse-don't-validate confines partiality to the layer that BUILDS the Model from untrusted input, so
   a reader operating on the already-trusted graph must be TOTAL: it may not throw. An offender is such
   a reader whose extracted twin (via `op-twin`) performs `:throws`.

   `:scope :global` (offenders are the authored reader ops). Naturally vacuous on a model-only build.
   The trust artifact is identified STRUCTURALLY (the `Kind` named `StructureDb`) — no per-op flag.
   FUKAN-SPECIFIC: the `StructureDb` binding is fukan's own trust artifact."
  (law "every trusted-core reader (its :in is the Model) is total — its realizing code performs no :throws"
    :scope :global
    :offenders '[?o]
    :where '[[?o :structure/of :canvas.vocab.code.operation/Operation] (not [?o :val/extracted true])
             [?ir :rel/from ?o] [?ir :rel/kind :in] [?ir :rel/to ?sch]
             [?sch :val/kind "ref"] [?nr :rel/from ?sch] [?nr :rel/kind :names] [?nr :rel/to ?k]
             [?k :structure/of :canvas.vocab.code.kind/Kind] [?k :entity/name "StructureDb"]
             (op-twin ?o ?e)
             [?pr :rel/from ?e] [?pr :rel/kind :performs] [?pr :rel/to ?eff] [?eff :val/name "throws"]]))

(defn totality-violations
  "The ENFORCED TOTALITY offenders — trusted-core READER operations whose realizing code is PARTIAL,
   as a set of op names. A reader is a modelled Operation whose `:in` references the trust artifact
   `StructureDb`; it operates on the trusted graph, so parse-don't-validate says it must be TOTAL. An
   entry is such a reader whose extracted twin throws. Empty ⇔ the modelled trusted core is total.
   Reads the single source of truth (the registered `Totality` law)."
  [db]
  (let [desc (-> (s/structure-by-tag ::Totality) :laws first :desc)]
    (->> (s/check db)
         (filter #(= desc (:law %)))
         (mapcat :offenders) (map first)
         (map #(:entity/name (cq/entity db %)))
         set)))
