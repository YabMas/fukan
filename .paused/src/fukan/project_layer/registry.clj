(ns fukan.project-layer.registry
  "Project layer registry — projection-input registrations per
   MODEL.md §10.3.

   Three kinds of projection inputs:
   - :root-prefix       — Clojure namespace prefix relative to module coord
   - :type-overrides    — Scalar name → malli rendering
   - :idioms            — vector of {:route <predicate-map> :body <data>}

   Composition mechanics (severity overrides, profiles, bundles) are
   deferred per DESIGN.md MVP commitments — Plan 5 ships per-entry
   registration only.")

(defn make-registry
  "Construct an empty project layer registry. The identity registry
   (empty root-prefix, no overrides, no idioms) is suitable for the
   fukan-on-fukan self-referential case."
  []
  {:root-prefix ""
   :type-overrides {}
   :idioms []})

(defn with-root-prefix
  "Set the Clojure namespace root prefix. fukan-on-fukan uses \"\"."
  [registry prefix]
  (assoc registry :root-prefix prefix))

(defn with-type-override
  "Register a per-Scalar-name type rendering. The Analyzer's type-translation
   consults the registry before falling back to the substrate default."
  [registry scalar-name malli-rendering]
  (assoc-in registry [:type-overrides scalar-name] malli-rendering))

(defn with-idiom
  "Append an idiom entry. entry shape:
     {:route {:primitive-kind <kw>? :projection-kind <kw>? :address-pattern <re>?}
      :body <data>}"
  [registry entry]
  (update registry :idioms conj entry))
