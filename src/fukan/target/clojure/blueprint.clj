(ns fukan.target.clojure.blueprint
  "Implementation Blueprint — per-projection ephemeral record assembled
   by the Projector (Plan 6). Per MODEL.md §7.7 + DESIGN.md
   'Implementation Blueprint — concrete shape'.

   Blueprint v1 shape:
     :case            :blueprint/v1
     :primitive-id    string (spec primitive id, e.g. 'm::Foo')
     :projection-kind keyword (:rule | :operation | :invariant | :schema | :test)
     :address         {:ns string :name string}
     :artifact-kind   keyword (:code/function | :code/data-structure)
     :signature       malli shape | arglist | nil
     :context         {:description :intent :related-edges} — Task 5
     :idioms          [<idiom-body>...] — Task 6
     :rendered        {:markdown :edn} — Task 7

   The Blueprint is NOT persisted — it's regenerated on every Projector
   call from current spec + project registry + Model state."
  (:refer-clojure :exclude [identity]))

(defn make
  "Construct a Blueprint v1. Required keys: :primitive-id, :projection-kind,
   :address, :artifact-kind. Optional: :signature, :context, :idioms, :rendered."
  [{:keys [primitive-id projection-kind address artifact-kind
           signature context idioms rendered]}]
  {:case            :blueprint/v1
   :primitive-id    primitive-id
   :projection-kind projection-kind
   :address         address
   :artifact-kind   artifact-kind
   :signature       signature
   :context         (or context {})
   :idioms          (or idioms [])
   :rendered        rendered})

(defn identity
  "Blueprint identity for inspection caching: (primitive-id, projection-kind)."
  [blueprint]
  [(:primitive-id blueprint) (:projection-kind blueprint)])

(defn to-edn
  "Serialise a Blueprint to EDN. Roundtrip is identity for inspection /
   Analyzer-verification consumption."
  [blueprint]
  (pr-str blueprint))
