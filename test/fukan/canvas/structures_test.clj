(ns fukan.canvas.structures-test
  "Integration smoke for the base vocabulary (fukan.canvas.structures): author a
   realistic module of real structures and check it; confirm planted violations
   are caught. Exercises Type (atomic + record-shaped), Effect, Event, and
   Function's four slots against the law engine."
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [fukan.canvas.core.structure :as s]
            [fukan.canvas.structures :refer [Type Effect Event Function]]))

(defn- laws-firing [db]
  (set (map (juxt :structure :law) (s/check db))))

(deftest realistic-module-checks-clean
  (testing "a well-formed module of Types/Effects/Events/Functions has no violations"
    (let [db (s/with-structures
               (s/within-module "infra.model"
                 (Type "Str")
                 (Type "Model")
                 (Type "User" (field [name Str] [id Str]))   ; record-shaped Type
                 (Effect "io")
                 (Event "ModelLoaded")
                 (Function "load-model"
                   (takes [src Str])
                   (gives Model)
                   (performs io)
                   (emits ModelLoaded))))]
      (is (empty? (s/check db))
          (str "expected a clean check, got: " (pr-str (s/check db)))))))

(deftest record-shaped-type-emits-field-relations
  (testing "a Type with fields carries labelled :field relations to its field Types"
    (let [db (s/with-structures
               (s/within-module "demo"
                 (Type "Str")
                 (Type "User" (field [name Str] [id Str]))))]
      (is (= #{["Str" "name"] ["Str" "id"]}
             (set (d/q '[:find ?to ?label
                         :in $
                         :where [?u :entity/name "User"]
                                [?r :rel/from ?u] [?r :rel/kind :field]
                                [?r :rel/to ?t] [?t :entity/name ?to]
                                [(get-else $ ?r :rel/label "") ?label]]
                       db)))))))

(deftest planted-violations-are-caught
  (testing "missing output, wrong-typed output, and wrong-typed effect all fire"
    (let [db (s/with-structures
               (s/within-module "demo"
                 (Type "Str")
                 (Type "Model")
                 (Effect "io")
                 (Function "no-output" (takes [x Str]))                  ; no gives
                 (Function "wrong-output" (gives io))                    ; gives an Effect
                 (Function "wrong-effect" (gives Model) (performs Model)))) ; performs a Type
          firing (laws-firing db)]
      (is (contains? firing [:Function "Function.gives requires exactly one (found none)"]))
      (is (contains? firing [:Function "Function.gives target must be a Type"]))
      (is (contains? firing [:Function "Function.performs target must be a Effect"])))))
