(ns fukan.target.clojure.blueprint
  "Implementation Blueprint — per-projection ephemeral record assembled
   by the Projector (Plan 6). Per MODEL.md §7.7 + DESIGN.md
   'Implementation Blueprint — concrete shape'.

   Tasks 1-7 fill this in; for now make-blueprint returns the identity
   record shape with :case :blueprint/v1.")

(defn make-blueprint
  "Construct an empty Blueprint v1 record. Fields populated by Projector
   tasks (1-6)."
  []
  {:case :blueprint/v1})
