(ns canvas.architecture.cozo.build
  "Self-spec: `fukan.cozo.build` — the datascript-free NATIVE build. Assembles
   instance-bearing vars straight into a Cozo substrate: reproduce the value-identity
   dedup + eid assignment + ref resolution datascript did implicitly, then write the
   datoms through the shared `cozo-mirror/load-datoms`. The bridge that lets Cozo be
   the substrate of record, no datascript intermediate — so it depends on the kernel
   (assembly + identity) and on cozo-mirror's write."
  (:require [canvas.vocab.code.operation :refer [Operation]]
            [canvas.vocab.code.module :refer [Module]]
            [canvas.architecture.cozo.db :as db]
            [canvas.architecture.cozo.mirror :as cmirror]
            [canvas.architecture.kernel.assemble :as kassemble]
            [canvas.architecture.kernel.substrate :as substrate]))

(Module cozo-build
  "Assemble instance-bearing vars into a Cozo substrate natively — the cozo analog
   of the kernel assembler, writing to Cozo instead of datascript."
  (Operation vars->cozo
    "Build a Cozo substrate (no datascript) from instance-bearing vars: roots → native datoms (dedup + eids + ref resolution) → load-datoms. Returns the open Cozo db."
    {:signature [:=> [:catn [:vars [:vector :any]]] db/CozoDb]
     :performs  [:throws]
     :delegates [substrate/value-content-key kassemble/emit-instances cmirror/load-datoms]})
  (Operation nss->cozo
    "Build a Cozo substrate natively from the instance-vars of the already-loaded ns-syms — the namespace-scan entry to vars->cozo."
    {:signature [:=> [:catn [:ns-syms [:vector :symbol]]] db/CozoDb]
     :performs  [:throws]
     :delegates [substrate/instance-value?]})
  (Operation with-grammar-cozo
    "Reflect the model's grammar into an already-built Cozo db (the datascript-free analog of grammar/with-grammar): query the structure tags, reflect → node/rel maps, UPSERT by :entity/id (reuse the eid of a ^:value Schema shared with the model), and insert the datoms. Returns the db."
    {:signature [:=> [:catn [:cdb db/CozoDb] [:extra-seeds :any]] db/CozoDb]
     :performs  [:throws]                          ; grammar/reflect throws on a dangling grammar ref
     :delegates [db/q cmirror/insert-datoms]})
  (Operation model->cozo
    "Native FULL build: canvas instance-vars + extraction {:roots :var-usages} facts → one native Cozo substrate with the :calls graph grounded (add-calls-cozo queries the built db for op eids) and the grammar reflected. Assembling all roots in one pass resolves cross-refs without a merge."
    {:signature [:=> [:catn [:ns-syms [:vector :symbol]] [:facts :map]] db/CozoDb]
     :performs  [:throws]
     :delegates [cmirror/load-datoms db/q with-grammar-cozo]}))
