(ns hooks.fukan.structure
  "clj-kondo hooks for the defstructure DSL (fukan.canvas.core.structure).

   defstructure defines a structure (slots + laws) AND a macro named for the
   structure used to author instances. Its body — (slot :rel (card Target) …)
   and (law …) — and the instance clauses — (takes [x Int]) … — are data, not
   code, so clj-kondo's default analysis flags every bare symbol. These hooks
   rewrite both forms into something analyzable:

     (defstructure Name \"doc\" body…)   → (def Name \"doc\")   ; Name resolves; body dropped
     (Name \"inst\" clauses…)            → (do \"inst\")         ; clauses dropped

   Each generated instance macro is registered against `instance` in
   .clj-kondo/config.edn by its fully-qualified name (clj-kondo can't discover
   the dynamically-generated macros)."
  (:require [clj-kondo.hooks-api :as api]))

(defn defstructure
  "(defstructure Name docstring & body) → (declare Name): Name resolves as a var
   without a (string) value, so its instance calls aren't flagged not-a-function;
   the slot/law body is dropped."
  [{:keys [node]}]
  (let [name-node (second (:children node))]
    {:node (api/list-node [(api/token-node 'declare) name-node])}))

(defn instance
  "(Structure \"name\" & clauses) → (Structure \"name\"): keep the call to the
   (declared) structure var and its name; drop the slot-clause DSL (bare slot
   names + by-name target/label symbols) rather than lint it as code. Staying a
   call avoids the unused-value flag a bare literal would draw in a body."
  [{:keys [node]}]
  (let [[head name-node] (:children node)]
    {:node (api/list-node
            (if name-node [head name-node] [head]))}))
