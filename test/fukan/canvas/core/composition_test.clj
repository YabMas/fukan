(ns fukan.canvas.core.composition-test
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [fukan.canvas.core.assemble :as a]
            [fukan.canvas.core.rules :as rules]
            [fukan.canvas.core.structure :as s :refer [defstructure]]))

;; ── product fixtures: a law-only facet + two includers ──────────────────────
(defstructure Linked
  "Facet (law-only): a node must participate in some directed relation."
  (law "no isolated node"
    :offenders '[?n]
    :where '[(not [?o :rel/from ?n]) (not [?i :rel/to ?n])]))

(defstructure Spoke
  "Includes the Linked facet; connected only by being pointed at."
  (includes Linked))

(defstructure Hub
  "Includes the Linked facet; points at Spokes."
  (includes Linked)
  (slot :spokes (many Spoke)))

(deftest includes-parsed-onto-sdef
  (testing "(includes …) collects facet tags onto the structure def"
    (is (= [:Linked] (:includes (s/structure-by-tag :Hub))))
    (is (= [:Linked] (:includes (s/structure-by-tag :Spoke))))
    (is (= []        (:includes (s/structure-by-tag :Linked))) "no includes → empty")))

(deftest derives-inclusion-rules
  (testing "each (includes Facet) yields a rule (Facet ?e) ⇐ (Concept ?e)"
    (let [rs (s/vocab-rules)]
      (is (some #(= % '[(Linked ?e) (Hub ?e)])   rs) "Hub includer rule")
      (is (some #(= % '[(Linked ?e) (Spoke ?e)]) rs) "Spoke includer rule"))))

(def lk-spoke (Spoke))                  ; pointed at by lk-hub → has incoming → connected
(def lk-hub   (Hub (spokes lk-spoke)))  ; has outgoing → connected
(def lk-iso   (Hub))                    ; no edges → isolated → OFFENDER of Linked's law

(defn- offenders-of [db law-desc]
  (->> (s/check db)
       (filter #(= law-desc (:law %)))
       (mapcat :offenders) (map first)
       (map #(:entity/name (d/entity db %)))
       set))

(deftest facet-law-ranges-over-includers
  (testing "a law on the Linked facet fires over Hub AND Spoke instances, via the inclusion rule"
    (let [db (a/assemble-vars [#'lk-spoke #'lk-hub #'lk-iso])]
      (is (= #{"lk-iso"} (offenders-of db "no isolated node"))
          "only the edge-less Hub is isolated; the connected Hub and the pointed-at Spoke are not"))))

(deftest facet-membership-is-entailed
  (testing "(Linked ?e) entails over every includer instance"
    (let [db    (a/assemble-vars [#'lk-spoke #'lk-hub #'lk-iso])
          names (set (d/q '[:find [?nm ...] :in $ %
                            :where (Linked ?e) [?e :entity/name ?nm]]
                          db (s/vocab-rules)))]
      (is (= #{"lk-spoke" "lk-hub" "lk-iso"} names)))))
