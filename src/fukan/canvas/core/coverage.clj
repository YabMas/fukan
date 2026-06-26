(ns fukan.canvas.core.coverage
  "Coverage — fukan's act↔code matching apparatus, built on the Lens act: an extracted reader that
   realizes a Lens (by a declared naming convention) must be covered by a declared Lens of the same
   focus. Its own concept beside core.lens, not bundled into it. References only core/substrate things
   (the Lens tag, :val/extracted, :entity/name, the configured prefix), so it carries no code-vocab
   dependency — which is what lets it live honestly in core."
  (:require [fukan.canvas.core.structure :as s :refer [defstructure]]
            [fukan.cozo.query :as cq]))

(defstructure ReaderConvention
  "Config: the naming convention by which a Lens act is realized as a reader — the reader's name is
   `prefix` + the lens's name. A project declares its convention; the Coverage law reads it."
  {:prefix :string})

(defn ^:export reader-realizes?
  "True when reader `rn` realizes the Lens named `ln` under convention `prefix` (rn = prefix+ln). The
   prefix-parameterized generalization of the old `probe-`-hardcoded predicate; compiled to Cozo via
   the predicate registry (`rn = concat(prefix, ln)`). Kept as the canonical definition + lint anchor."
  [rn prefix ln]
  (= rn (str prefix ln)))

(defstructure Coverage
  "Law-holder: every extracted reader following a declared `ReaderConvention` (named prefix+X) must be
   covered by a declared `Lens` named X — the read-surface dual of Encapsulation for fukan's Lens act.
   The dual is deliberately NOT enforced (a Lens needs no reader). `:scope :global`; vacuous on a
   model-only build (no ReaderConvention ⇒ no offenders). Reader side is ELEMENT-AGNOSTIC (no
   `:structure/of Operation` filter — only convention-prefixed functions are ever so named)."
  (law "every extracted reader (prefix+X) is covered by a declared Lens X"
    :scope :global
    :offenders '[?r]
    :where '[[?rc :structure/of ::ReaderConvention] [?rc :val/prefix ?p]
             [?r :val/extracted true] [?r :entity/name ?rn]
             [(clojure.string/starts-with? ?rn ?p)]
             ;; covered ⇔ some convention prefix + Lens name reconstructs the reader name. The
             ;; convention is re-read INSIDE the not-join (local ?p2) so the helper-rule head projects
             ;; only ?rn — which `?rn = concat(?p2, ?ln)` binds — keeping it range-restricted (a
             ;; projected outer ?p would be unbound inside the rule, the module-corresponds? gotcha).
             (not-join [?rn]
               [?rc2 :structure/of ::ReaderConvention] [?rc2 :val/prefix ?p2]
               [?l :structure/of :fukan.canvas.core.lens/Lens] [?l :entity/name ?ln]
               [(fukan.canvas.core.coverage/reader-realizes? ?rn ?p2 ?ln)])]))

(defn uncovered-readers
  "The LENS-COVERAGE worklist — extracted readers with no covering Lens, as a set of reader names.
   Reads the registered `Coverage` law's offenders (the single source of truth)."
  [db]
  (let [desc (-> (s/structure-by-tag ::Coverage) :laws first :desc)]
    (->> (s/check db)
         (filter #(= desc (:law %)))
         (mapcat :offenders) (map first)
         (map #(:entity/name (cq/entity db %)))
         set)))
