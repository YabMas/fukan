(ns fukan.cozo.predicate-port-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.cozo.query :as cq]
            ;; loading the vocab module runs its self-registration of the module-corresponds? port
            [canvas.vocab.code.module]))

(deftest module-corresponds-port-is-vocab-registered-not-kernel-hardcoded
  (testing "the module-corresponds? Cozo port lives in vocab, not the generic kernel compiler"
    ;; vocab self-registered the port + its synthetic CozoScript rules (present at runtime)
    (is (contains? (deref @#'cq/predicate-registry) 'canvas.vocab.code.module/module-corresponds?)
        "module.clj registered the predicate port")
    (is (contains? (deref @#'cq/synthetic-rules) "r_module_corresponds")
        "and its synthetic CozoScript rules came in with the registration")
    ;; the de-leak: the generic compiler SOURCE names no code-vocab predicate/tag
    (let [src (slurp "src/fukan/cozo/query.clj")]
      (is (not (re-find #"module-corresponds" src)) "no module-corresponds? hardcoded in the compiler")
      (is (not (re-find #"code\.module/Module" src)) "no Module tag named in the compiler"))))
