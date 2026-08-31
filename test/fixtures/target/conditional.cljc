(ns conditional
  "A `.cljc` file, which the extractor meets from both sides at once: clj-kondo analyses it for
   `:clj` AND for `:cljs`, so a `defmethod` in EITHER arm emits a marker and a satisfier node. The
   bounds read has to answer for both arms or a node stands with its body calls missing — this
   seam's characteristic failure, and not one a read error would announce.")

(defmulti shade :kind)

(defn tag [s] (:tag s))

(defn label [s] (:label s))

(defn register! [f] f)

;; A reader conditional is a SELECTION to `:read-cond :allow`, whatever features it is handed:
;; naming both `:clj` and `:cljs` picks whichever arm matches first — the `:clj` one here — and
;; the other arm's forms are read and thrown away, extents and all. The `:cljs` method is emitted
;; either way, so what a selecting read loses is exactly and only its body.
#?(:clj  (defmethod shade :on-jvm [s] (tag s))
   :cljs (defmethod shade :on-js [s] (label s)))

;; The SPLICING spelling, one wrapper deeper: the arms are vectors rather than forms. `#?@` is not
;; legal at top level, so it always arrives inside a container — which makes it the nesting case
;; too, and the case where a read that selects does something worse than lose a body. The
;; unselected arm's marker still has to land in SOME extent, and the smallest one containing it is
;; then the `do` itself — so that method claims the sibling below as its own, which is the
;; invented edge the whole extent discipline exists to refuse.
(do
  #?@(:clj  [(defmethod shade :spliced-jvm [s] (tag s))]
      :cljs [(defmethod shade :spliced-js [s] (label s))])
  (register! shade))
