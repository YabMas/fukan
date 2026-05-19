(ns fukan.vocabulary.boundary.analyzer
  "Boundary AST → kernel content. Per MODEL.md §8.2.

   Two file shapes (Plan 3a parser AST):
   - Module-bound: declarations are mix of :use, :fn, :exports.
   - Subsystem-bound: declarations are use + one :subsystem.

   This namespace is built up across Tasks 2-7."
  (:require [fukan.model.build :as build]
            [fukan.model.primitives :as p]
            [fukan.model.relations :as r]
            [fukan.model.type :as t]
            [fukan.model.vocabulary :as v]))

;; ---------------------------------------------------------------------------
;; Shape detection
;; ---------------------------------------------------------------------------

(defn- shape-of
  "Returns :module-bound, :subsystem-bound, or :mixed (a structural error)."
  [declarations]
  (let [kinds (set (map :type declarations))
        has-module-decls    (or (kinds :fn) (kinds :exports))
        has-subsystem-decls (kinds :subsystem)]
    (cond
      (and has-module-decls has-subsystem-decls) :mixed
      has-subsystem-decls                        :subsystem-bound
      :else                                      :module-bound)))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- qualify [coord local-name]
  (str coord "::" local-name))

(defn- translate-type-ref
  "Convert a Plan 3a parser type-ref into a kernel Type value.
   Cross-module refs (:qualified) resolve through use-aliases when possible;
   else fall through to a Scalar placeholder (Plan 3c will validate).
   Optional wrappers are unwrapped — callers handle optionality at the
   parameter/return-type level."
  [tr coord use-aliases]
  (case (:kind tr)
    :simple
    (t/make-scalar (:name tr))

    :optional
    (translate-type-ref (:inner tr) coord use-aliases)

    :generic
    (case (:name tr)
      "List" (t/make-collection
               (translate-type-ref (first (:params tr)) coord use-aliases)
               :sequential)
      "Set"  (t/make-collection
               (translate-type-ref (first (:params tr)) coord use-aliases)
               :unique)
      "Map"  (let [[k v] (:params tr)]
               (t/make-collection
                 (translate-type-ref v coord use-aliases)
                 (t/keyed (translate-type-ref k coord use-aliases))))
      (t/make-scalar (:name tr)))

    :qualified
    (if-let [resolved-coord (get use-aliases (:ns tr))]
      (t/make-composite-named (qualify resolved-coord (:name tr)))
      (t/make-scalar (str (:ns tr) "/" (:name tr))))))

(defn- param->kernel
  "Convert a Plan 3a fn-param into a kernel Parameter value record.
   p/make-parameter is positional: [name type optional? ordinal]."
  [coord use-aliases ordinal param]
  (let [tr        (:type-ref param)
        optional? (= :optional (:kind tr))
        type-val  (translate-type-ref tr coord use-aliases)]
    (p/make-parameter (:name param) type-val optional? ordinal)))

(defn- ensure-module-container
  "Find the module-Container at `coord`, or create a minimal stub if absent.
   Returns updated model."
  [model coord]
  (if (build/get-primitive model coord)
    model
    (build/add-primitive model
                         (p/make-container {:id coord :label coord}))))

(defn- add-operation-to-boundary
  "Append an Operation id to the module-Container's boundary.operations
   slot. Creates the Boundary if absent. Stores plain id strings per
   the Boundary schema and the Allium analyzer's established convention."
  [model coord op-id]
  (let [container  (build/get-primitive model coord)
        boundary   (or (:boundary container)
                       (p/make-boundary {:id    (str coord "::boundary")
                                         :label coord
                                         :operations []}))
        boundary'  (update boundary :operations conj op-id)
        container' (assoc container :boundary boundary')]
    (assoc-in model [:primitives coord] container')))

;; ---------------------------------------------------------------------------
;; Per-decl handlers (Tasks 3-7 fill these in)
;; ---------------------------------------------------------------------------

(defn- analyze-use [model _decl _coord _use-aliases]
  ;; use declarations are analyzer-internal (handled at pipeline level
  ;; via use-aliases); no kernel content produced here.
  model)

(defn- resolve-rule-ref
  "Translate a fn-body :triggers ref to a kernel Rule id.
   Local refs qualify against `coord`; qualified refs resolve through
   `use-aliases`. Returns nil if the alias is unknown — caller decides
   whether to emit a warning or skip."
  [trigger-ref coord use-aliases]
  (case (:kind trigger-ref)
    :local     (qualify coord (:name trigger-ref))
    :qualified (when-let [resolved (get use-aliases (:ns trigger-ref))]
                 (qualify resolved (:name trigger-ref)))))

(defn- warn! [msg ctx]
  (binding [*out* *err*]
    (println (str "[boundary-analyzer] " msg " " (pr-str ctx)))))

(defn- emit-binding-edge
  "Best-effort emission of an R4 triggers edge + Boundary::Binding tag.
   Skips silently (with warning) on unresolved rule refs or kernel errors."
  [model op-id trigger-ref returns-text coord use-aliases]
  (if-let [rule-id (resolve-rule-ref trigger-ref coord use-aliases)]
    (let [edge            (r/make-edge :relation/triggers
                                       (r/primitive-ref op-id)
                                       (r/primitive-ref rule-id))
          edge-id         (r/edge-identity edge)
          binding-payload (cond-> {}
                            returns-text (assoc :returns_expression returns-text))
          tag-app         (v/make-tag-application
                            (cond-> {:tag    {:namespace "Boundary" :name "Binding"}
                                     :target {:case :target/edge :edge-identity edge-id}}
                              (seq binding-payload) (assoc :payload binding-payload)))]
      (try
        (-> model
            (build/add-edge edge)
            (build/add-tag-application tag-app))
        (catch Exception e
          (warn! "binding edge emission failed"
                 {:op op-id :rule rule-id :ex (ex-message e)})
          model)))
    (do
      (warn! "unresolved trigger ref"
             {:op op-id :trigger trigger-ref :use-aliases (keys use-aliases)})
      model)))

(defn- analyze-fn-declare-new
  "Per MODEL.md §8.2: `fn name(params) -> R` declares a new Operation on the
   bearing module-Container's boundary.operations. When body has :triggers,
   emits R4 (triggers: Operation → Rule) edge with Boundary::Binding tag
   carrying :returns_expression payload when present."
  [model decl coord use-aliases]
  (let [fn-name  (:name decl)
        op-id    (qualify coord fn-name)
        params   (vec (map-indexed (fn [i p] (param->kernel coord use-aliases i p))
                                   (:params decl)))
        return-t (when-let [tr (:return-type decl)]
                   (translate-type-ref tr coord use-aliases))
        op       (p/make-operation
                   (cond-> {:id op-id :label fn-name :parameters params}
                     return-t (assoc :return-type return-t)))
        m0       (-> model
                     (ensure-module-container coord)
                     (build/add-primitive op)
                     (add-operation-to-boundary coord op-id))
        m1       (build/add-tag-application
                   m0
                   (v/make-tag-application
                     {:tag    {:namespace "Boundary" :name "Function"}
                      :target {:case :target/operation :id op-id}}))
        body     (:body decl)]
    (if (and body (:triggers body))
      (emit-binding-edge m1 op-id (:triggers body) (:returns body)
                         coord use-aliases)
      m1)))

(defn- analyze-fn [model decl coord use-aliases]
  (case (:form decl)
    :declare-new (analyze-fn-declare-new model decl coord use-aliases)
    ;; Tasks 4-5 add :local-attach and :foreign-attach handlers.
    model))

(defn- analyze-exports [model _decl _coord _use-aliases]
  ;; Task 6 implements.
  model)

(defn- analyze-subsystem [model _decl _coord _use-aliases]
  ;; Task 7 implements.
  model)

(defn- analyze-decl [model decl coord use-aliases]
  (case (:type decl)
    :use       (analyze-use       model decl coord use-aliases)
    :fn        (analyze-fn        model decl coord use-aliases)
    :exports   (analyze-exports   model decl coord use-aliases)
    :subsystem (analyze-subsystem model decl coord use-aliases)
    (throw (ex-info "Unknown .boundary declaration type"
                    {:type :boundary-shape-error
                     :decl-type (:type decl)
                     :coord coord}))))

;; ---------------------------------------------------------------------------
;; Public entrypoint
;; ---------------------------------------------------------------------------

(defn analyze-file
  "Apply a parsed .boundary AST to the model. `coord` is the file's
   coordinate (root-relative, no extension). `use-aliases` is the
   file-local map of alias → canonical-coord for cross-module resolution.

   Returns updated model. Throws on mixed module/subsystem shape."
  [model ast coord use-aliases]
  (let [decls (:declarations ast)
        shape (shape-of decls)]
    (when (= shape :mixed)
      (throw (ex-info "mixed module-bound and subsystem-bound shapes in one file"
                      {:type :boundary-shape-error
                       :coord coord})))
    (reduce (fn [m decl] (analyze-decl m decl coord use-aliases))
            model
            decls)))
