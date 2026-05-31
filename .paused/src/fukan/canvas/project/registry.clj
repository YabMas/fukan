(ns fukan.canvas.project.registry
  "Project-lens registry — surfaces the set of `defmethod project` entries
   loaded into the substrate's multimethod.

   Registration mechanism: every projection namespace requires
   `fukan.canvas.project.core` and `defmethod`s itself onto the
   multimethod. This namespace queries the multimethod for the registry
   view; it does NOT require projection namespaces directly. Per-lens
   loader namespaces (e.g. `fukan.canvas.project.clojure`) own the
   explicit require list for their projections. Mirrors the
   substrate/registry split in `fukan.canvas.lens.*` while keeping the
   substrate independent of any one lens's projection set.

   To register a new Clojure-lens projection: drop a file under
   `src/fukan/canvas/project/clojure/<name>.clj`, define its
   `defmethod project [:clojure <dispatch-key>]`, then add a require in
   `fukan.canvas.project.clojure`. External-language lenses ship their
   own loader alongside, on the consuming project's classpath."
  (:require [fukan.canvas.project.core :as core]))

(defn all-projections
  "Return the vector of dispatch keys ([lens-id dispatch-key] pairs)
   currently registered on the project multimethod. Useful for
   discovery + tests. The :default method is filtered out."
  []
  (->> (methods core/project)
       (keys)
       (remove #(= :default %))
       (sort-by (juxt first second))
       vec))

(defn projection-for
  "Return the `defmethod project` fn registered for `[lens-id element]`,
   or nil if none. Resolves dispatch via the same key the multimethod
   uses (Affordance → :canvas-role; Type/Module → :model-element-kind).
   The substrate's `:default` fallback is filtered out — a nil return
   means \"no projection registered\" rather than \"would throw\"."
  [lens-id element]
  (let [k          [lens-id (core/dispatch-key-of element)]
        candidate  (get-method core/project k)
        default    (get-method core/project :default)]
    (when-not (= candidate default)
      candidate)))
