(ns canvas.libs.allium.parser-test
  (:require [clojure.test :refer [deftest is]]
            [canvas.libs.allium.parser :as port]
            [fukan.canvas.core.substrate.store :as store]
            [datascript.core :as d]))

(deftest canvas-build-produces-non-empty-store
  (let [db (port/build-canvas)]
    (is (some? db))
    (is (>= (count (store/all-modules db)) 1))))

(deftest canvas-build-has-expected-entities
  (let [db (port/build-canvas)
        names (->> (d/q '[:find ?n :where [?e :entity/name ?n]] db)
                   (map first)
                   set)]
    (is (contains? names "libs.allium.parser") "module name present")
    (is (contains? names "ParsedAllium") "ParsedAllium record present")
    (is (contains? names "ParseFailure") "ParseFailure record present")
    (is (contains? names "ParserPure") "ParserPure invariant present")
    (is (contains? names "ParseFileDelegates") "ParseFileDelegates invariant present")
    (is (contains? names "DeclarationOrderPreserved") "DeclarationOrderPreserved invariant present")
    (is (contains? names "parse_allium") "parse_allium function present")
    (is (contains? names "parse_file") "parse_file function present")))
