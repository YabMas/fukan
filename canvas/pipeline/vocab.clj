(ns canvas.pipeline.vocab
  "Vocabulary for modelling fukan's build pipeline — established SEPARATELY from
   the model spec (`canvas.pipeline.model`), mirroring the demos' vocab/model
   split. A vocab-only canvas spec: it `defstructure`s the vocabulary and has no
   `build-canvas`, so canvas-source loads it (registering the structures) but
   ingests no instances from it.

   `Shape` is VALUE-typed (^:value): a shape IS its composition, so structurally-
   equal shapes — e.g. the StructureDb type-shape that recurs across every stage's
   signature — collapse to ONE node. That is the value-identity payoff, seen here
   in a real fukan self-model rather than a toy demo. `Stage` is an entity that
   consumes input shapes and produces an output shape."
  (:require [fukan.canvas.core.structure :refer [defstructure]]))

(defn ^:export read-shape
  "Expand a data-LITERAL shape into Shape construction clauses — one level; nested
   literals are re-expanded by the interpreter (so a shape reads like ordinary
   Clojure data):
     Foo        (symbol)        → a \"type\" leaf naming the Type Foo
     [X]        (1-elem vector) → a \"list\" of shape X
     {:f X, …}  (map)           → a \"record\" with fields f: X, …"
  [data]
  (cond
    (symbol? data) [(list 'kind "type") (list 'type data)]
    (vector? data) [(list 'kind "list") (list 'of (first data))]
    (map?    data) (into [(list 'kind "record")]
                         (map (fn [[k v]] (list 'of [(symbol (name k)) v])) data))
    :else (throw (ex-info (str "not a shape literal: " (pr-str data)) {:data data}))))

(defstructure ^:value Shape
  "A structural type-shape, value-identified. `:kind` is the combinator — \"type\"
   (a leaf naming a Type), \"list\" (a homogeneous sequence of its child shape), or
   \"record\" (labelled child shapes); `:of` are child shapes (recursive); `:type`
   names the referenced Type for a \"type\" leaf. Author shapes as data literals
   (see `read-shape`): `Db`, `[Db]`, `{:entity-maps [EntityMap] :ref-txs [RefTx]}`."
  (slot :kind (one :String))
  (slot :of   (many Shape))
  (slot :type (optional Type))
  (reader read-shape)
  ;; the kind discriminator (a leaf value) driving a structural law
  (law "a \"type\" shape must name a Type"
    :offenders '[?s]
    :where '[[?s :val/kind "type"]
             (not-join [?s] [?r :rel/from ?s] [?r :rel/kind :type])]))

(defstructure Stage
  "A pipeline stage (entity): consumes input shapes, produces exactly one output
   shape, and may call downstream stages."
  (slot :in    (many Shape) :label-as :param)
  (slot :out   (one  Shape))
  (slot :calls (many Stage)))
