(ns fukan.canvas.core.declarations-golden-test
  "Characterization lock for the kernel's declaration-registry emission: the full set of derived
   Terms (`structure/terms-of`) and Laws (`structure/laws-of`) over fukan's SELF-MODEL vocabulary
   must not CHANGE silently. Not a spec of WHAT the rules are — a snapshot gate on the sole rule
   emitter (both seams dispatch the declaration handlers).

   Scoped to the `canvas.vocab.*`/`canvas.principles.*` structures (required below so they are all
   registered), NOT `all-structures` — the global registry also accumulates test fixtures during a
   full run, which would make the snapshot unstable."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [fukan.cozo.law]                        ; registers the check engine (load side-effect)
            [fukan.canvas.core.structure :as s]
            ;; force the full self-model vocabulary to register
            [canvas.vocab.grouping]
            [canvas.vocab.type]
            [canvas.vocab.grammar]
            [canvas.vocab.code.kind]
            [canvas.vocab.code.effect]
            [canvas.vocab.code.operation]
            [canvas.vocab.code.module]
            [canvas.vocab.code.subsystem]
            [canvas.principles.parse-dont-validate]
            [canvas.principles.declared-effects]
            [canvas.principles.layered-architecture]
            [canvas.principles.deep-modules]))

(defn self-model-structures
  "The registered structures defined in the self-model vocabulary — stable regardless of which test
   fixtures are also loaded into the global registry."
  []
  (filter #(when-let [ns (namespace (:tag %))]
             (or (str/starts-with? ns "canvas.vocab")
                 (str/starts-with? ns "canvas.principles")))
          (s/all-structures)))

(defn normalized-terms
  "Every derived Term over the self-model, as a stable sorted-set of pr-str'd rules."
  []
  (into (sorted-set) (map pr-str) (s/terms-of (self-model-structures))))

(defn normalized-laws
  "Every Law over the self-model, as a stable sorted-set of pr-str'd law data."
  []
  (into (sorted-set)
        (for [sdef (self-model-structures), law (s/laws-of sdef)]
          (pr-str (select-keys law [:key :desc :offenders :where :rules])))))

;; The frozen snapshot (captured 2026-07-06 against pre-refactor emission). Any drift in derived
;; Terms/Laws during the Stage-A re-plumb fails here; diff `(normalized-terms)`/`(normalized-laws)`
;; live against the failing set to localize the family whose handler drifted.
(def ^:private golden-terms {:count 60  :hash 1126070098})
(def ^:private golden-laws  {:count 100 :hash 1844879140})

(deftest terms-are-stable
  (let [terms (normalized-terms)]
    (is (= (:count golden-terms) (count terms)) "self-model Term count changed")
    (is (= (:hash golden-terms) (hash terms))
        "derived Terms changed — emission must be behavior-preserving")))

(deftest laws-are-stable
  (let [laws (normalized-laws)]
    (is (= (:count golden-laws) (count laws)) "self-model Law count changed")
    (is (= (:hash golden-laws) (hash laws))
        "derived Laws changed — emission must be behavior-preserving")))
