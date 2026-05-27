(ns fukan.canvas.instruct.registry
  "Scenario registry — surfaces the set of registered Layer-B scenario
   declarations.

   Registration is explicit, mirroring the lens-registry + canvas-source
   pattern: every scenario namespace declares a `scenario` var, and this
   namespace pulls them together into `known-scenarios`. Adding a new
   scenario means: drop a file under `src/fukan/canvas/instruct/<name>.clj`,
   require it here, and conj its var into `known-scenarios`.

   Invalid scenarios are skipped with a stderr warning (mirroring the
   lens-registry warn-not-throw convention)."
  (:require [clojure.string :as str]
            [fukan.canvas.instruct.core :as core]
            ;; Scenario namespaces — each declares a `scenario` var. Add a
            ;; require here and conj its var into `known-scenarios` below.
            [fukan.canvas.instruct.drift-close :as drift-close]))

(def known-scenarios
  "Vector of registered scenario vars. To register a new scenario:
     1. require the scenario namespace above
     2. conj its `scenario` var here."
  [#'drift-close/scenario])

(defn- warn-invalid!
  [scenario-var issues]
  (binding [*out* *err*]
    (println (str "canvas.instruct.registry: skipping " scenario-var
                  " — invalid scenario: " (str/join "; " issues)))))

(defn all-scenarios
  "Return a seq of all valid scenario maps registered. Invalid vars are
   skipped with a warn-to-stderr message (does not throw)."
  []
  (->> known-scenarios
       (keep (fn [scenario-var]
               (let [m      @scenario-var
                     issues (core/validate-scenario m)]
                 (if (seq issues)
                   (do (warn-invalid! scenario-var issues) nil)
                   m))))))

(defn scenario-by-id
  "Return the registered scenario map with the given id, or nil if not
   found."
  [id]
  (first (filter #(= id (:scenario-id %)) (all-scenarios))))
