(ns canvas.descent.source
  "DESCENT STEP — the `Source` in-fold, descended with TEETH (the first generative-descent slice).
   `canvas.subject/Source` is a pure portrait: it declares a `:polarity [:enum \"design-down\"
   \"code-up\"]` in-fold but asserts no law, so nothing forces its realization to cover both
   flavours. This spec adds the missing pressure: a realization edge (`SourceRealizer` names the
   `Module` that realizes one polarity) plus a structural-witness LAW the descent must satisfy.

   The same law reads three ways: DOWN = verify (it runs in `check`), UP = carve and GAP = prompt
   (those readings live in `fukan.descent`). It joins to the reflected `Source` by its tag — the
   manifest's existing mechanism — so NO kernel structure-reference slot is lifted. This is the
   model↔realization correspondence for the in-fold, so it lives in its own seam (like
   `fukan.target.correspondence`), NOT on the pure `Source` portrait (which must not name
   realizers). The editorial `canvas.manifest/z-source` entry is left as-is; this is its toothed
   companion."
  (:require [fukan.canvas.core.structure :refer [defstructure]]
            [lib.code :refer [Module]]
            [lib.grouping :refer [Grouping]]
            ;; the :witnesses [:enum …] scalar checks through the malli type dialect
            [lib.type.malli]
            ;; the realizer Modules — the same vars the editorial manifest names
            [canvas.architecture.canvas-source :refer [canvas-source]]
            [canvas.architecture.target :refer [target-clojure]]))

(defstructure SourceRealizer
  "A toothed realization edge for the `canvas.subject/Source` in-fold: the `Module` in `:by`
   realizes the `:witnesses` polarity flavour. The structural-witness law asserts the descent
   obligation — every polarity the `Source` portrait declares must have a realizer — joining to
   the reflected `Source` via its tag (the manifest's mechanism), so no core slot is lifted."
  {:witnesses [:enum "design-down" "code-up"]    ; the in-fold flavour this realizer covers
   :by        Module}                            ; the code module that realizes it
  ;; STRUCTURAL WITNESS (descent obligation, strength b): every polarity of the reflected `Source`
  ;; in-fold is witnessed by a `SourceRealizer`. `:scope :global` — the offenders are the
  ;; unwitnessed polarity choice nodes, not `SourceRealizer`s. The leading `Source`-tag clause is
  ;; the guard: vacuous on any db where the subject is not reflected. Negation routes through the
  ;; `(witnessed …)` rule so the zero-realizer case dodges datascript's empty-relation not-join gotcha.
  (law "every polarity of the Source in-fold is witnessed by a realizer"
    :scope :global
    :offenders '[?choice]
    :rules '[[(witnessed ?polarity)
              [?w :structure/of :canvas.descent.source/SourceRealizer]
              [?w :val/witnesses ?polarity]]]
    :where '[[?src :val/tag ":canvas.subject/Source"]
             [?pr :rel/from ?src] [?pr :rel/label "polarity"] [?pr :rel/to ?enum]
             [?cr :rel/from ?enum] [?cr :rel/kind :choice] [?cr :rel/to ?choice]
             [?choice :val/value ?polarity]
             (not (witnessed ?polarity))]))

;; the two realizers of the in-fold — design authored DOWN, code extracted UP
(SourceRealizer w-design {:witnesses "design-down" :by canvas-source})
(SourceRealizer w-code   {:witnesses "code-up"     :by target-clojure})

(Grouping source-descent
  {:child [w-design w-code]})
