(ns tasks.kondo-test
  "Tests for the clj-kondo hook generator: scanning defstructure forms into the
   `:analyze-call` entries each generated instance-constructor macro needs."
  (:require [clojure.edn]
            [clojure.test :refer [deftest is testing]]
            [tasks.kondo :as kondo]))

;; ── form classification: which forms intern a constructor macro ──────────────

(deftest structure-name-classifies-forms
  (testing "a plain defstructure yields its constructor name"
    (is (= 'Module (kondo/structure-name '(defstructure Module "doc" {:exposes [:* Operation]})))))
  (testing "a ^:value defstructure still interns a constructor"
    (is (= 'Effect (kondo/structure-name '(defstructure ^:value Effect "doc" {:name :string})))))
  (testing "an aliased s/defstructure is recognised too"
    (is (= 'Attr (kondo/structure-name '(s/defstructure Attr "doc" {:required :boolean})))))
  (testing "a realized-as concept interns NO constructor (excluded)"
    (is (nil? (kondo/structure-name '(defstructure Flagged "doc" (realized-as '[(Sum ?e) [?e :val/kind "a"]]))))))
  (testing "non-defstructure forms yield nil"
    (is (nil? (kondo/structure-name '(defn foo [x] x))))
    (is (nil? (kondo/structure-name '(defrelation-coproduct :xview "doc" :via :x))))
    (is (nil? (kondo/structure-name "a docstring")))))

;; ── source → fully-qualified constructor names ───────────────────────────────

(deftest qualified-names-from-source
  (testing "constructor names are qualified by the file's ns, in source order, realized-as excluded"
    (let [src (str "(ns demo.vocab (:require [x :refer [defstructure]]))\n"
                   "(defstructure Foo \"d\" {:a :string})\n"
                   "(defstructure ^:value Bar \"d\" {:b :int})\n"
                   "(defstructure Gone \"d\" (realized-as '[(Foo ?e)]))\n"
                   "(defn helper [x] x)")]
      (is (= ['demo.vocab/Foo 'demo.vocab/Bar]
             (kondo/qualified-names src)))))
  (testing "a source with no ns form yields no names (nothing to qualify)"
    (is (= [] (kondo/qualified-names "(defstructure Loose \"d\" {:a :string})")))))

;; ── the analyze-call map shape ───────────────────────────────────────────────

(deftest analyze-call-map-points-each-name-at-the-generic-hook
  (is (= '{a/B hooks.fukan.structure/instance
           c/D hooks.fukan.structure/instance}
         (kondo/analyze-call-map ['c/D 'a/B]))))

;; ── scanning the real source tree (the integration validator) ────────────────

(deftest scan-finds-known-structures-and-excludes-realized
  (let [names (set (kondo/scan ["lib" "canvas" "demos" "test"]))]
    (testing "live vocab constructors are found"
      (is (contains? names 'lib.code/Module))
      (is (contains? names 'lib.code/Operation))
      (is (contains? names 'lib.type.malli/Schema))
      (is (contains? names 'lib.grouping/Grouping))
      (is (contains? names 'fukan.canvas.core.structure-test/Function)))
    (testing "realized-as concepts intern no constructor → excluded"
      (is (not (contains? names 'fukan.canvas.core.composition-test/Flagged)))
      (is (not (contains? names 'fukan.canvas.core.composition-test/VariantA))))))

;; ── the generated config shape ───────────────────────────────────────────────

(deftest generated-config-wraps-the-analyze-call-map
  (let [cfg (kondo/generated-config ["lib"])]
    (is (= 'hooks.fukan.structure/instance
           (get-in cfg [:hooks :analyze-call 'lib.code/Module])))
    (is (contains? (get-in cfg [:hooks :analyze-call]) 'lib.grouping/Grouping))))

;; ── the staleness guard: the committed generated config must be current ──────

(deftest generated-config-file-is-current
  (testing "the committed .clj-kondo/generated/config.edn matches a fresh scan
            (regenerate with `clojure -M:kondo` if this fails)"
    (is (= (kondo/generated-config kondo/default-dirs)
           (clojure.edn/read-string (slurp ".clj-kondo/generated/config.edn"))))))
