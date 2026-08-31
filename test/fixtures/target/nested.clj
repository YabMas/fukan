(ns nested
  "The two ways positional attribution loses a method body that the ordinary spelling never
   shows — the READ that has to finish, and the BOUND that has to be a form's extent. They share a
   file because they fail the same way — silently, and at the scale of a whole node's worth of
   edges: the satisfier is emitted either way, and only its calls go missing, so the graph is
   simply smaller than the code with nothing anywhere saying so."
  (:require [poly :as p]
            [sample :as s]))

(defn tag [s] (:tag s))

(defn label [s] (:label s))

;; THE READ. `::p/…` and `#::p{…}` are ordinary Clojure — `p` is an alias of a required namespace
;; — and both are `Invalid token` to a reader resolving aliases in its OWN namespace, which is the
;; only namespace an extractor has: `poly` is the adopter's, and is never loaded in the JVM doing
;; the extracting. A read that throws yields no bounds for the file, so EVERY method body below it
;; is dropped, not just the one that follows the literal.
(def defaults #::p{:mode ::p/plain})

;; THE SAME READ, one demand further: resolving every alias is not enough, they have to resolve
;; APART. Sent to one namespace these two keys become one key, and a map with a duplicate key is a
;; read ERROR — so a file the extractor could read is made unreadable BY the extractor, and the
;; drop below fires on source that was never in doubt. It is the worse half of the same failure:
;; an unreadable file at least tells the truth about itself. The set is the same literal in
;; different brackets and rejects the duplicate identically.
(def keyed {::p/id 1 ::s/id 2})

(def tags #{::p/tag ::s/tag})

(defmulti shade :kind)

;; The ordinary spelling, whose body is attributed only if the file read at all.
(defmethod shade :light [s] (tag s))

;; THE BOUND. A defmethod nested inside a top-level form is legal and its satisfier node is
;; emitted exactly like the one above. On top-level rows alone the enclosing row is the `do`'s,
;; which matches no marker — so these body calls would vanish while the nodes claiming them
;; stood. Two of them, because the marker rows have to SEPARATE the nested methods as well as
;; find them: the first one's body stops where the second one starts, and nowhere else.
(do
  (defmethod shade :dark [s] (label s))
  (defmethod shade :grey [s] (tag s)))
