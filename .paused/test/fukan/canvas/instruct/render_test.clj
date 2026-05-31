(ns fukan.canvas.instruct.render-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fukan.canvas.instruct.render :as render]))

(def ^:private sample-instruction
  {:scenario-id      :code-side/example
   :code-spec        {:projection-kind :clojure/example
                      :lens-id         :clojure
                      :model-element-kind :Type
                      :model-element-id "m/type/X"
                      :target {:path "x.clj" :namespace "x" :symbol "X"}
                      :template "(def X :x)"
                      :prose "doc"}
   :scenario-context {:foo "bar"}
   :rendered         "# Implementation instruction\n\nbody"})

(deftest to-markdown-returns-rendered
  (is (= "# Implementation instruction\n\nbody"
         (render/to-markdown sample-instruction))))

(deftest section-joins-non-blank-blocks
  (let [out (render/section "## Title" "first" nil "" "second")]
    (is (str/includes? out "## Title"))
    (is (str/includes? out "first"))
    (is (str/includes? out "second"))
    (testing "blank/nil blocks are skipped, no double blanks"
      (is (not (re-find #"\n\n\n" out))))))

(deftest bulleted-renders-list
  (is (= "- a\n- b\n- c"
         (render/bulleted ["a" "b" "c"])))
  (testing "nil/empty produces empty string"
    (is (= "" (render/bulleted [])))
    (is (= "" (render/bulleted nil)))))

(deftest fenced-wraps-in-language-block
  (let [out (render/fenced "clojure" "(def x 1)")]
    (is (str/starts-with? out "```clojure\n"))
    (is (str/ends-with? out "\n```"))))
