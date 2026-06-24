(ns fukan.cozo.build-test
  "P4: the native (datascript-free) build produces a substrate content-identical to
   the datascript build mirrored — same query results, including the value-identity
   dedup (a `^:value` shared across ops must collapse to ONE node)."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.core.assemble :as a]
            [fukan.canvas.projection.canvas-source :as canvas-source]
            [fukan.cozo.db :as db]
            [fukan.cozo.mirror :as mirror]
            [fukan.cozo.build :as build]
            [fukan.cozo.reading :as reading]
            [canvas.vocab.code.operation :as operation]
            [canvas.vocab.code.module :as module]))

;; modules ma/mb with a cross-module delegation; both ops :performs [:io], so the
;; ^:value :io Effect must value-dedup to a single shared node.
(declare t-b-op)
(operation/Operation ^{:name "a-op"} t-a-op {:delegates [t-b-op] :performs [:io]})
(operation/Operation ^{:name "b-op"} t-b-op {:performs [:io]})
(module/Module ^{:name "ma"} t-ma {:exposes [t-a-op]})
(module/Module ^{:name "mb"} t-mb {:exposes [t-b-op]})

(def ^:private vs [#'t-a-op #'t-b-op #'t-ma #'t-mb])

(defn- io-nodes [cdb]
  (count (db/q cdb "?[e] := *t_str[e, 'val/name', 'io']")))

(deftest native-build-matches-datascript-mirror
  (testing "native build == datascript-mirror build: module-deps + value-identity dedup"
    (let [ds       (a/assemble-vars vs)
          mirrored (mirror/mirror ds)
          native   (build/vars->cozo vs)]
      (try
        (is (= #{["ma" "mb"]} (reading/module-dependencies native))
            "precondition: the cross-module delegation is a dependency")
        (is (= (reading/module-dependencies mirrored) (reading/module-dependencies native))
            "module-dependencies agree across build paths")
        (is (= 1 (io-nodes native)) "the shared ^:value :io Effect collapses to one node")
        (is (= (io-nodes mirrored) (io-nodes native)) "value-identity dedup agrees")
        (finally (db/close mirrored) (db/close native))))))

(deftest native-canvas-build-matches-datascript
  (testing "native build of the FULL canvas design model == datascript (module-deps at scale)"
    (let [ds       (canvas-source/build)              ; requires + assembles every canvas spec
          nss      (canvas-source/canvas-namespaces)
          mirrored (mirror/mirror ds)
          native   (build/nss->cozo nss)]
      (try
        (is (seq (reading/module-dependencies native))
            "precondition: the real design model has module dependencies")
        (is (= (reading/module-dependencies mirrored) (reading/module-dependencies native))
            "full design-model module-dependencies agree across build paths")
        (finally (db/close mirrored) (db/close native))))))
