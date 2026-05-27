(ns fukan.canvas.lens.registry
  "Lens registry — exposes the `lens` vars declared in loaded
   `fukan.canvas.lens.*` namespaces.

   Registration is explicit, mirroring the canvas-source/canvas-builders
   pattern: add a require to the ns form below, then conj the new lens
   var into `known-lenses`. \"Drop a file\" really means \"drop a file +
   add two lines here.\" Keeping the registry explicit (rather than
   classpath scanning) matches the rest of fukan and makes the wiring
   greppable.

   Each registered var is validated on lookup. Invalid lenses are
   skipped with a stderr warning (matching the canvas-source warn-not-throw
   convention for authoring-time issues that shouldn't block startup)."
  (:require [clojure.string :as str]
            [fukan.canvas.lens.core :as core]
            ;; Lens namespaces — each must declare a `lens` var. Add a require
            ;; here and conj the var into `known-lenses` below.
            [fukan.canvas.lens.consistency :as consistency]
            [fukan.canvas.lens.patterns :as patterns]
            [fukan.canvas.lens.tar-pit :as tar-pit]))

(def known-lenses
  "Vector of registered lens vars. To register a new lens:
     1. require the lens namespace above
     2. conj its `lens` var here."
  [#'patterns/lens
   #'consistency/lens
   #'tar-pit/lens])

(defn- warn-invalid!
  [lens-var issues]
  (binding [*out* *err*]
    (println (str "canvas.lens.registry: skipping " lens-var
                  " — invalid lens: " (str/join "; " issues)))))

(defn all-lenses
  "Return the seq of all valid lens maps registered. Invalid lens vars are
   skipped with a warn-to-stderr message (does not throw)."
  []
  (->> known-lenses
       (keep (fn [lens-var]
               (let [lens-map @lens-var
                     issues   (core/validate-lens lens-map)]
                 (if (seq issues)
                   (do (warn-invalid! lens-var issues) nil)
                   lens-map))))))

(defn lens-by-id
  "Return the registered lens map with the given id, or nil if not found."
  [id]
  (first (filter #(= id (:id %)) (all-lenses))))
