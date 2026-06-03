(ns fukan.target.clojure
  "A trivial Clojure extractor — the seam proof for the extraction plug-point.
   Reads `defn` top-level forms (no eval) and emits `Defn` code-structure instances.
   Deliberately mechanical: no recognition; the plug-point is what's under test."
  (:require [clojure.java.io :as io]
            [fukan.canvas.core.structure :as s :refer [defstructure]])
  (:import [java.io PushbackReader]))

(defstructure Defn
  "A function definition extracted from source — name (entity) + arity."
  (slot :arity (one :Int)))

(defstructure Capability
  "An authored abstraction that should be realized by an extracted Defn (by name).
   The cross-layer law is verifiable only on a graph holding both Capabilities (authored)
   and Defns (extracted) — the value prop in miniature."
  (slot :doc      (optional :String))
  (slot :realizes (one :String))   ; the name of the Defn expected to realize it
  (law "a capability is realized by an extracted defn"
    :offenders '[?c]
    :where '[[?c :val/realizes ?n]
             (not [?d :structure/of :Defn] [?d :entity/name ?n])]))

(defn- read-forms
  "Read all top-level forms from `path` as data (no eval)."
  [path]
  (binding [*read-eval* false]
    (with-open [r (PushbackReader. (io/reader path))]
      (loop [acc []]
        (let [form (read {:eof ::eof} r)]
          (if (= form ::eof) acc (recur (conj acc form))))))))

(defn- defn-form->entry
  "A `(defn name [args] …)` form → {:name :arity}, else nil. Single-arity only."
  [form]
  (when (and (seq? form) (= 'defn (first form)))
    (when-let [arglist (first (filter vector? form))]
      {:name (str (second form)) :arity (count arglist)})))

(defn extract
  "Extract a structure-db of `Defn` instances from the Clojure source at `path`.
   Pure: builds the db via the substrate's programmatic emission API."
  [path]
  (let [entries (keep defn-form->entry (read-forms path))]
    (s/with-structures*
      (fn []
        (s/within-module* (str "code:" path)
          (fn []
            (doseq [{:keys [name arity]} entries]
              (s/instantiate! :Defn name (list (list 'arity arity))))))))))
