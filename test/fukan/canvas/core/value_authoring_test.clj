(ns fukan.canvas.core.value-authoring-test
  (:require [clojure.test :refer [deftest is]]
            [fukan.canvas.core.structure :as s]))

(deftest instance-value-record-holds-composition
  (let [iv (s/->InstanceValue :Box "A" nil {} [] false)]
    (is (= :Box (:tag iv)))
    (is (= "A" (:name iv)))
    (is (s/instance-value? iv))))

(def ^:private sample-iv (s/->InstanceValue :Box "A" nil {} [] false))

(deftest var-id-is-fully-qualified
  (is (= "fukan.canvas.core.value-authoring-test/sample-iv"
         (s/var-id #'sample-iv))))
