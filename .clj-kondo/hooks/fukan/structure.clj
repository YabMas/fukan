(ns hooks.fukan.structure
  "clj-kondo hooks for the defstructure DSL (fukan.canvas.core.structure).

   defstructure defines a structure (slots + laws) AND a macro named for the
   structure used to author instances. Its body — the slots map and (law …) —
   and the instance {slot → value} map (by-name target/label symbols, datalog
   payloads) are data, not code, so clj-kondo's default analysis flags every
   bare symbol. These hooks rewrite both forms into something analyzable:

     (defstructure Name \"doc\" body…)    → (declare Name)        ; Name resolves; body dropped
     (Name sym \"doc\"? {…} nested…)      → (declare sym …kids)   ; def-emitting: vars resolve
     (Name \"doc\"? {…})                  → (Name \"doc\"?)         ; expression: body dropped

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

(defn- instance-syms
  "The name symbols a def-emitting instance form interns: its own (the second
   child) plus, recursively, every nested `(Tag sym …)` member's."
  [node]
  (let [[_ sym & body] (:children node)
        sym-node? (fn [n] (and n (try (symbol? (api/sexpr n)) (catch Exception _ false))))]
    (into [sym]
          (mapcat (fn [c]
                    (when (and (= :list (api/tag c))
                               (let [[h s] (:children c)]
                                 (and (sym-node? h) (sym-node? s))))
                      (instance-syms c)))
                  body))))

(defn instance
  "Instance authoring is data, not code — rewrite to the analyzable skeleton.

   Def-emitting `(Structure sym \"doc\"? {…} nested…)` → `(declare sym …kids)`:
   the interned vars resolve (declare draws no unused-public-var); the slots map
   (by-name targets, labels, datalog payloads) is dropped rather than linted.

   Expression `(Structure \"doc\"? {…})` → `(Structure \"doc\"?)`: keep the call to
   the (declared) structure var; staying a call avoids the unused-value flag a
   bare literal would draw in a body."
  [{:keys [node]}]
  (let [[head x] (:children node)
        sexpr-of (fn [n] (when n (try (api/sexpr n) (catch Exception _ nil))))]
    (if (symbol? (sexpr-of x))
      ;; ^{:name "…"}-meta'd name symbols are meta nodes, not token nodes — hence sexpr
      {:node (api/list-node (into [(api/token-node 'declare)] (instance-syms node)))}
      {:node (api/list-node (if (string? (sexpr-of x)) [head x] [head]))})))
