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
            [fukan.target.clojure.blueprint :as bp]
            [fukan.target.clojure.types :as types]))

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

(defn- field->malli-pair
  [registry field]
  [(keyword (:name field)) (types/render registry (:type-ref field))])

(defn- param->malli-pair
  [registry param]
  [(keyword (:name param)) (types/render registry (:type-ref param))])

(defn- signature-for
  "Return the expected-signature map for a primitive + projection-kind pair.
   Note: :primitive/expression (invariant substrate addresses) is not yet
   routable through the Projector's primitive-keyed entry point — invariant
   projection is a future path. Drop those cases for Plan 6 MVP."
  [registry primitive projection-kind]
  (case [(:kind primitive) projection-kind]
    [:primitive/operation :projection-kind/operation]
    {:arglist      (mapv :name (:parameters primitive))
     :param-types  (mapv #(types/render registry (:type-ref %)) (:parameters primitive))
     :return-malli (when-let [rt (:return-type primitive)]
                     (types/render registry rt))}

    ;; arity-zero forms
    [:primitive/operation :projection-kind/test]  {:arglist [] :return-malli nil}
    [:primitive/rule      :projection-kind/rule]  {:arglist [] :return-malli nil}
    [:primitive/rule      :projection-kind/test]  {:arglist [] :return-malli nil}

    ;; schema map shape
    [:primitive/container :projection-kind/schema]
    {:malli-shape (into [:map] (map #(field->malli-pair registry %) (:fields primitive)))}

    [:primitive/event :projection-kind/schema]
    {:malli-shape (into [:map] (map #(param->malli-pair registry %) (:parameters primitive)))}

    nil))

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
          artifact-kind  (artifact-kind-for projection-kind)
          signature      (signature-for registry primitive projection-kind)]
      (bp/make
        {:primitive-id    primitive-id
         :projection-kind projection-kind
         :address         address
         :artifact-kind   artifact-kind
         :signature       signature}))))
