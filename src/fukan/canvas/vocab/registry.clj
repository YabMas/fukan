(ns fukan.canvas.vocab.registry
  "The tag-definition registry: vocabulary terms as declared data.

   Vocabularies *register* their terms here at load time — each vocab declares a
   `tag-definitions` vector and calls `register!` — so the registry is a runtime
   collection, not a central list. Drop a vocab file and its terms register;
   remove it and they're gone (the plugin model / delete-and-replace). Each term
   is `{:tag :family :payload :edges? :doc}`; the construct-kit interpreter
   (vocab/construct) reads them to build nodes, and canvas-source projects them
   into the substrate db as :tagdef/* entities.

   :payload is a descriptor (:arrow/:record/:prose/:trigger/:on-emits/:none);
   :edges is the production-directive list (see vocab/construct).")

(defonce ^:private terms (atom {}))

(defn register!
  "Register a seq of tag-definitions. Idempotent per :tag (reload overwrites)."
  [defs]
  (swap! terms merge (into {} (map (juxt :tag identity)) defs))
  nil)

(defn all
  "Every registered tag-definition."
  []
  (vals @terms))

(defn by-tag
  "The registered tag-definition for `tag`, or nil."
  [tag]
  (get @terms tag))
