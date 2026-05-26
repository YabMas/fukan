(ns canvas.model.effect-test
  (:require [clojure.test :refer [deftest is]]
            [canvas.model.effect :as port]
            [fukan.canvas.core.substrate.store :as store]
            [datascript.core :as d]))

(deftest canvas-build-produces-non-empty-store
  (let [db (port/build-canvas)]
    (is (some? db))
    (is (>= (count (store/all-modules db)) 1))))

(deftest canvas-build-has-expected-entities
  (let [db   (port/build-canvas)
        names (->> (d/q '[:find ?n :where [?e :entity/name ?n]] db)
                   (map first)
                   set)]
    (is (contains? names "EffectKindIsClosed"))
    (is (contains? names "EffectIdentityIsTriple"))
    (is (contains? names "CanonicaliseIsMethodologyDelegated"))
    (is (contains? names "make_effect"))
    (is (contains? names "effect_identity"))
    (is (contains? names "canonicalise"))))
