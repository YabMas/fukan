(ns fukan.libs.coordinate-test
  (:require [clojure.test :refer [deftest is]]
            [fukan.libs.coordinate :as coord]))

(deftest canonicalise-strips-allium-extension
  (is (= "a/b" (coord/canonicalise-path "host" "a/b.allium"))))

(deftest canonicalise-strips-boundary-extension
  (is (= "a/b" (coord/canonicalise-path "host" "a/b.boundary"))))

(deftest canonicalise-handles-relative-current-dir
  (is (= "src/a" (coord/canonicalise-path "src/host" "./a.allium"))))

(deftest canonicalise-handles-relative-parent-dir
  (is (= "src/a" (coord/canonicalise-path "src/sub/host" "../a.allium"))))

(deftest canonicalise-handles-chained-parent-dirs
  ;; Regression: cross-module refs that climb more than one level
  ;; (e.g. `../../model/spec.allium` from web/views/cytoscape) must
  ;; collapse every leading `../`, not just the first one.
  (is (= "fukan/model/spec"
         (coord/canonicalise-path "fukan/web/views/cytoscape"
                                  "../../model/spec.allium")))
  (is (= "x"
         (coord/canonicalise-path "a/b/c/host" "../../../x.allium"))))

(deftest canonicalise-handles-bare-paths
  (is (= "a/b" (coord/canonicalise-path "anywhere" "a/b.allium")))
  (is (= "x"   (coord/canonicalise-path "anywhere" "x"))))

(deftest canonicalise-handles-root-relative-host
  (is (= "a" (coord/canonicalise-path "" "./a.allium"))))
