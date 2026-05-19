(ns fukan.target.clojure.analyzer
  "Clojure Target Analyzer — Phase 6 of the build pipeline.

   Walks Clojure source files under a configured root, identifies
   function definitions and def-shaped data structures, emits
   Code.* Artifacts plus projects edges with per-edge :validity
   from every spec primitive that should have a Clojure realisation.

   Per MODEL.md §7.6 and DESIGN.md 'Implementation linkage'.

   Tasks 1-10 fill this in. For now run returns model unchanged.")

(defn run
  "Run the Clojure Analyzer on the model. Tasks 6-10 implement; stub
   returns model unchanged."
  [model _project-registry]
  model)
