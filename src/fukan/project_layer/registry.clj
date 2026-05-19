(ns fukan.project-layer.registry
  "Project layer registry — projection-input registrations per
   MODEL.md §10.3. The Analyzer (Plan 5) and the future Projector
   (Plan 6) consume this.

   Task 2 fills in the shape; for now make-registry returns an empty
   registry suitable for fukan-on-fukan's identity case (empty root
   prefix, no overrides, no idioms).")

(defn make-registry
  "Construct a project layer registry. The empty case (no args) is
   the identity registry — empty root prefix, no type-translation
   overrides, no idiom entries. Suitable for fukan-on-fukan."
  []
  {:root-prefix ""
   :type-overrides {}
   :idioms []})
