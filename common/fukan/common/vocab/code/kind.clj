(ns fukan.common.vocab.code.kind
  "Code vocab — `Kind`: a named data-shape, the nominal handle for a value type a Module owns.
   (Definition only; Kinds carry no model↔code correspondence and are not extracted.)"
  (:require [fukan.canvas.core.structure :refer [defstructure]]
            [fukan.common.typing.malli :refer [Schema]]))

(defn ^:export shape->slots
  "Kind's authoring syntax (the `(syntax …)` hook, map → map): a positional malli body is
   wrapped into the `:shape` slot; a map body passes through (the named-slot form)."
  [b] (if (map? b) b {:shape b}))

(defstructure Kind
  "A named data-shape — the nominal handle for a value type a Module owns. Its body IS
   its shape: a `Schema` (the pluggable typing) authored positionally — a scalar
   (`:int`), a record (`[:map …]`), a union (`[:or …]`), a collection (`[:vector …]` /
   `[:map-of …]`), or an arrow (`[:=> …]`). A Kind with NO body is an opaque external
   (a Cozo db, a filesystem reality) — honestly shapeless. A member of at most one
   Module (the owner; other modules adopt it by NAME — they reference, never contain)."
  {:shape [:? Schema]}                          ; its shape, when it has one (authored positionally)
  (syntax shape->slots)                         ; positional malli body → the :shape slot
  (law "a Kind is a member of at most one Module"
    {:offenders [?k]
     ;; the full tag keyword: `module` requires `kind` (Module's :child names Kind), so the
     ;; symbol form would need a cyclic require — `is` resolution rides the acyclic ns graph.
     :rules [[(kind-home ?k ?m) (is ?m :fukan.common.vocab.code.module/Module)
              (contains ?m ?k)]]
     :where [(kind-home ?k ?m1) (kind-home ?k ?m2) [(not= ?m1 ?m2)]]}))
