(ns canvas.language.shape
  "The fukan-on-fukan model's DATA layer — its own grammar, built directly on the
   core primitive (no shared/base vocabulary; canvas exposes mechanics only). A
   `Kind` is a named atomic type; a `Shape` is a structural type-expression over
   Kinds, value-identified and authored as a Clojure data literal.

   (Named `Kind`, not `Type`, only because the structure registry is a single
   global tag namespace — `structure_test` co-loads a `Type` fixture in the test
   run. That collision is the standing core finding for truly decoupling models;
   for now distinct names suffice.)

   This is a vocab-only canvas spec: it `defstructure`s a grammar and has no
   `build-canvas`, so canvas-source loads it (registering the structures) but
   ingests no instances."
  (:require [fukan.canvas.core.structure :refer [defstructure]]))

(defstructure Kind
  "A named atomic type — a leaf in a Shape (e.g. Db, NsSymbol, File).")

(defn ^:export read-shape
  "Expand a data-LITERAL shape into Shape construction clauses — one level; nested
   literals are re-expanded by the interpreter:
     Foo        (symbol)        → a \"type\" leaf naming the Kind Foo
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
   (a leaf naming a Kind), \"list\" (a homogeneous sequence of its child shape), or
   \"record\" (labelled child shapes); `:of` are child shapes (recursive); `:type`
   names the referenced Kind for a \"type\" leaf. Author as data: `Db`, `[Db]`,
   `{:entity-maps [EntityMap] :ref-txs [RefTx]}`."
  (slot :kind (one :String))
  (slot :of   (many Shape))
  (slot :type (optional Kind))
  (reader read-shape)
  (law "a \"type\" shape must name a Kind"
    :offenders '[?s]
    :where '[[?s :val/kind "type"]
             (not-join [?s] [?r :rel/from ?s] [?r :rel/kind :type])]))
