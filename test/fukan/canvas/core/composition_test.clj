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
