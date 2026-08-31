(ns poly-ext
  "A SECOND namespace supplying `poly`'s multimethod — the cross-module case `poly.clj` alone
   cannot show. Everything about the supply side that matters is a statement about the boundary
   between these two files: whose dependency the method's body calls are, and whose they are not."
  (:require [poly]))

(defn width [s] (:w s))

(defn register! [f] f)

;; `some->` expands to `if`/`let`/`->` usages clj-kondo emits with NO source position. A usage
;; with no row cannot be positionally attributed to anything, and must pass through rather than
;; sink the extraction — 150 of clojure-mcp's 7763 usages are like this.
(defmethod poly/render-shape :rect [s] (some-> s width))

;; A TOP-LEVEL form after a method, calling ACROSS a namespace boundary — and INDENTED, which is
;; the case a column test waves through. Leading whitespace before a top-level form is legal, so
;; nothing about where this sits distinguishes it from the method's body except that the reader
;; knows a new top-level form starts here. Without that bound it is captured as part of the method,
;; and because the callee is constrained to no namespace the invented edge leaves the file:
;; `poly-ext` would be recorded as calling into `poly` on the strength of where a form happens to
;; sit, in front of a `Band` law that reads that graph globally. It comes FIRST because it is the
;; sharp case: a column-1 form here would shield it by ending the body itself.
  (register! poly/describe)

;; And the same at column 1, the ordinary spelling — dropped for the same reason, which is what
;; already happens to every top-level call in a file with no methods in it.
(register! poly/area)

;; TWO TOP-LEVEL FORMS ON ONE LINE. Ownership does not change by row: a bound computed from start
;; rows cannot separate these two, and the second is no more part of the first than any other
;; sibling. Its callee is in `poly`, so capturing it would invent a cross-namespace edge.
(defmethod poly/render-shape :inline [s] (width s)) (register! poly/area)

;; A NESTED method, and a sibling beside it inside the same container. The `do` is the top-level
;; form, so the method's own extent is the only thing that distinguishes its body from what
;; follows it — and the method keeps its body rather than being dropped for being nested.
(do
  (defmethod poly/render-shape :nested [s] (width s))
  (register! poly/describe))

;; A tagged literal beside an equal untagged value. The extent read is PERMISSIVE — it answers for
;; every tag, because an adopter's namespaces are not loaded here — but permissive must not mean
;; LOSSY: unwrapping `#inst "2020-01-01"` to its bare string collides it with the string beside it,
;; and `Duplicate key` costs the whole file's method bodies. The tag is kept, so the two stay
;; distinct. This is the third spelling of one class — valid source a permissive read turns
;; unreadable — after a reader conditional losing an arm and two aliases colliding.
(def stamps #{#inst "2020-01-01" "2020-01-01"})

(defmethod poly/render-shape :tagged [s] (width s))
