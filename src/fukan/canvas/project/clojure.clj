(ns fukan.canvas.project.clojure
  "Clojure-lens loader — explicit requires for every projection that
   registers on `[:clojure ...]`. fukan-on-fukan's reference lens.

   Adding a new Clojure projection: drop a file under
   `src/fukan/canvas/project/clojure/<name>.clj`, define its
   `defmethod project [:clojure <dispatch-key>]`, then add a require
   here. The registry surface (`fukan.canvas.project.registry`) queries
   the multimethod for the registered set — this loader's only job is
   to ensure those defmethods are run by the time a consumer asks."
  (:require [fukan.canvas.project.clojure.value-to-def]
            [fukan.canvas.project.clojure.type-to-malli]
            [fukan.canvas.project.clojure.event-to-schema]
            [fukan.canvas.project.clojure.function-to-defn]
            [fukan.canvas.project.clojure.invariant-to-predicate]
            [fukan.canvas.project.clojure.rule-to-predicate]
            [fukan.canvas.project.clojure.getter-to-defn]
            [fukan.canvas.project.clojure.checker-to-defn]))
