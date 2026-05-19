(ns fukan.target.clojure.projector
  "Projector — assembles Implementation Blueprints on demand.

   Six-component universal projection mechanic per MODEL.md §7.7:
     1. Canonical address          (Task 2)
     2. Artifact kind              (Task 2)
     3. Expected signature         (Task 3)
     4. Type renderings            (Task 4)
     5. Surrounding model context  (Task 5)
     6. Selected idioms            (Task 6)
   Plus serialisation               (Task 7).

   Application of the same mechanic the Analyzer (Plan 5) uses, in
   reverse: spec primitive → Blueprint that the LLM (generation) and the
   Analyzer (verification) both consume."
  (:require [clojure.string :as str]
            [fukan.target.clojure.address :as addr]
            [fukan.target.clojure.blueprint :as bp]))

(defn- module-coord-of
  [primitive-id]
  (when (and (string? primitive-id) (str/includes? primitive-id "::"))
    (first (str/split primitive-id #"::" 2))))

(def ^:private function-kinds
  #{:projection-kind/rule :projection-kind/operation
    :projection-kind/invariant :projection-kind/test})

(def ^:private data-structure-kinds
  #{:projection-kind/schema})

(defn- artifact-kind-for
  [projection-kind]
  (cond
    (contains? function-kinds projection-kind)       :code/function
    (contains? data-structure-kinds projection-kind) :code/data-structure
    :else (throw (ex-info "unknown projection-kind"
                          {:projection-kind projection-kind}))))

(defn project
  "Project a primitive into a Blueprint.

   Tasks 1-2 cover the address + artifact-kind assembly. Tasks 3-7
   extend with signature, context, idioms, and rendered serialisations."
  [model registry primitive-id projection-kind]
  (let [primitive (get-in model [:primitives primitive-id])]
    (when-not primitive
      (throw (ex-info "primitive not found in model"
                      {:primitive-id primitive-id})))
    (let [primitive-kind (:kind primitive)
          module-coord   (module-coord-of primitive-id)
          address        (addr/canonical registry primitive-kind projection-kind
                                         module-coord (:label primitive))
          artifact-kind  (artifact-kind-for projection-kind)]
      (bp/make
        {:primitive-id    primitive-id
         :projection-kind projection-kind
         :address         address
         :artifact-kind   artifact-kind}))))
