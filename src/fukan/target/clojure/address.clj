(ns fukan.target.clojure.address
  "Convention-driven canonical address resolution for Clojure Target.

   Per DESIGN.md 'Implementation linkage' / 'Address resolution':
   - Module ns: {root-prefix}.{module-coord-dots}
   - Type-shaped projections (DataStructure): PascalCase preserved
   - Function-shaped projections: PascalCase → kebab-lower;
                                  snake_case → kebab-lower
   - Test projection: ns + '-test', name + '-test'."
  (:require [clojure.string :as str]))

(def ^:private datastructure-kinds
  ;; Both `:primitive/container` (record types) and `:primitive/event`
  ;; route through `:projection-kind/schema` for the data-structure
  ;; lane. Address derivation is therefore symmetric between the two
  ;; primitive kinds — Phase 7 Task 4 gap 3 locks this in a test so
  ;; Layer A's `event-to-schema` projection reuses the existing
  ;; machinery without divergence.
  #{:projection-kind/schema})

(def ^:private function-kinds
  #{:projection-kind/rule
    :projection-kind/operation
    :projection-kind/invariant
    :projection-kind/test})

(defn module-ns
  "Compute the Clojure namespace from a module coord.
   '{root-prefix}.{coord-with-/-as-.}', with root-prefix omitted when empty.

   Per-segment underscore→hyphen conversion: canvas module names use
   underscores in some places (e.g. agent.views_loader,
   project_layer.registry) while real Clojure namespaces use hyphens
   (agent.views-loader, project-layer.registry). Each dot-separated
   segment of the resulting module ns is normalised to kebab-case so
   drift derives addresses that match the actual ns instead of emitting
   false :absent findings."
  [registry module-coord]
  (let [pfx (:root-prefix registry "")
        dotted (str/replace module-coord #"/" ".")
        kebabbed (->> (str/split dotted #"\.")
                      (map #(str/replace % #"_" "-"))
                      (str/join "."))]
    (if (str/blank? pfx)
      kebabbed
      (str pfx "." kebabbed))))

(defn- pascal->kebab-lower
  "ProcessSubmission → process-submission."
  [s]
  (-> s
      (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
      (str/lower-case)))

(defn- snake->kebab-lower
  "submit_order → submit-order."
  [s]
  (str/replace s #"_" "-"))

(defn local-name
  "Compute the local Clojure name (within the module ns) for a primitive
   given its kind + projection-kind. The primitive-label is the
   canvas-side declaration name (entity-name); address derivation
   kebab-cases it for function-shaped projections and preserves
   PascalCase for data-structure projections."
  [primitive-kind projection-kind primitive-label]
  (cond
    (contains? datastructure-kinds projection-kind)
    primitive-label  ;; PascalCase preserved

    (contains? function-kinds projection-kind)
    (-> primitive-label
        snake->kebab-lower
        pascal->kebab-lower)

    :else
    (throw (ex-info "unknown projection-kind for address resolution"
                    {:projection-kind projection-kind
                     :primitive-kind primitive-kind
                     :label primitive-label}))))

(defn canonical
  "Build the full canonical address {:ns <string> :name <string>}.
   Test projections append '-test' to both ns and name."
  [registry primitive-kind projection-kind module-coord primitive-label]
  (let [base-ns (module-ns registry module-coord)
        base-name (local-name primitive-kind projection-kind primitive-label)]
    (if (= :projection-kind/test projection-kind)
      {:ns (str base-ns "-test") :name (str base-name "-test")}
      {:ns base-ns :name base-name})))
