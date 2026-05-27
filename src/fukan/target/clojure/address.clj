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

(defn- blank-label?
  "True when the primitive-label is missing or whitespace-only — the
   signal that drives `:projection-kind/invariant`'s fallback to
   `kebab(invariant-name)` (Phase 7 Task 4 gap 2)."
  [s]
  (or (nil? s)
      (and (string? s) (str/blank? s))))

(defn local-name
  "Compute the local Clojure name (within the module ns) for a primitive
   given its kind + projection-kind.

   `opts` (optional) supports:
   - `:invariant-name` — fallback name used when `:projection-kind/invariant`
     is requested and `primitive-label` is blank/nil (e.g. the canvas
     declaration omitted its `holds-that` clause). The fallback is
     kebab-cased the same way as a present holds-that clause."
  ([primitive-kind projection-kind primitive-label]
   (local-name primitive-kind projection-kind primitive-label nil))
  ([primitive-kind projection-kind primitive-label opts]
   (let [label (if (and (= :projection-kind/invariant projection-kind)
                        (blank-label? primitive-label)
                        (some? (:invariant-name opts)))
                 (:invariant-name opts)
                 primitive-label)]
     (cond
       (contains? datastructure-kinds projection-kind)
       label  ;; PascalCase preserved

       (contains? function-kinds projection-kind)
       (-> label
           snake->kebab-lower
           pascal->kebab-lower)

       :else
       (throw (ex-info "unknown projection-kind for address resolution"
                       {:projection-kind projection-kind
                        :primitive-kind primitive-kind
                        :label primitive-label}))))))

(defn canonical
  "Build the full canonical address {:ns <string> :name <string>}.
   Test projections append '-test' to both ns and name.

   `opts` (optional) is forwarded to `local-name` — see its docstring for
   the supported keys (notably `:invariant-name` for the Phase 7 gap-2
   holds-that-absent fallback)."
  ([registry primitive-kind projection-kind module-coord primitive-label]
   (canonical registry primitive-kind projection-kind module-coord primitive-label nil))
  ([registry primitive-kind projection-kind module-coord primitive-label opts]
   (let [base-ns (module-ns registry module-coord)
         base-name (local-name primitive-kind projection-kind primitive-label opts)]
     (if (= :projection-kind/test projection-kind)
       {:ns (str base-ns "-test") :name (str base-name "-test")}
       {:ns base-ns :name base-name}))))
