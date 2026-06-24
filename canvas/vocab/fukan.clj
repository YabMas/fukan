(ns canvas.vocab.fukan
  "TEMPORARY holding pen for the FUKAN-SPECIFIC correspondence tools — `Totality` (a trusted-core
   reader whose `:in` is the Model must be total) and `LensCoverage` (every bespoke probe reader
   realizes a declared `Lens`). These are not generic code-correspondence: they bind to fukan's own
   `StructureDb` trust artifact, its `Lens` act, and its `probe-` reader convention. Kept whole here
   (binding NOT lifted) until the parameterized-trait groundwork lets the general law-shapes move to
   the code vocab and the bindings move to fukan's design. The op pairing `op-twin` lives in
   `canvas.vocab.code.module`, referenced here via datalog injection."
  (:require [fukan.canvas.core.structure :as s :refer [defstructure]]
            [fukan.cozo.query :as cq]))

(defn ^:export reader-realizes-lens?
  "True when a bespoke probe reader named `rn` (e.g. \"probe-survey\") realizes the Lens named
   `ln` (\"survey\") — the reader name is the lens name under the `probe-` realization prefix.
   The Lens↔reader analog of `module-corresponds?`: a NAME bridge, so the correspondence needs no
   `:realizes` relation (mirroring `Realization`'s authored↔extracted name match)."
  [rn ln]
  (= rn (str "probe-" ln)))

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

(defstructure LensCoverage
  "Law-holder for the LENS↔READER correspondence — the lens-analog of `Encapsulation`, at the read
   surface. fukan's bespoke probe readers (`probe-X`, the model-db→finding leaves in
   `projection/probes`) are the CODE realization of its declared `Lens` instruments: a reader is a
   focus run richly. So every reader must be COVERED by a declared Lens of the same focus — you do not
   write a bespoke reader without first naming its focus as a `Lens` (intent), exactly as
   `Encapsulation` forbids an undeclared public op. The DUAL is deliberately NOT enforced: a Lens needs
   no reader (a reasoning lens runs generically through its `:select`), so this guards reader→lens only.

   Match is on NAME (the `probe-` realization prefix; `reader-realizes-lens?`). `:scope :global`;
   vacuous on a model-only build. FUKAN-SPECIFIC: the `Lens` act + `probe-` convention are fukan's."
  (law "every extracted probe reader is covered by a declared Lens of the same focus"
    :scope :global
    :offenders '[?r]
    :where '[[?r :structure/of :canvas.vocab.code.operation/Operation] [?r :val/extracted true] [?r :entity/name ?rn]
             [(clojure.string/starts-with? ?rn "probe-")]
             (not-join [?rn]
               [?l :structure/of :fukan.canvas.core.lens/Lens] [?l :entity/name ?ln]
               [(canvas.vocab.fukan/reader-realizes-lens? ?rn ?ln)])]))

(defn uncovered-readers
  "The LENS-COVERAGE worklist — extracted probe readers (`probe-X`) with no declared `Lens` of the
   same focus, as a set of reader names. Empty ⇔ every bespoke reader's focus is declared as a Lens
   instrument (the dual — a Lens with no reader — is allowed). Reads the single source of truth (the
   registered `LensCoverage` law)."
  [db]
  (let [desc (-> (s/structure-by-tag ::LensCoverage) :laws first :desc)]
    (->> (s/check db)
         (filter #(= desc (:law %)))
         (mapcat :offenders) (map first)
         (map #(:entity/name (cq/entity db %)))
         set)))

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
