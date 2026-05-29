(ns fukan.canvas.project.core
  "Project-lens substrate — the lens contract + validation + the
   multimethod that every projection registers against.

   A *projection* takes a generic Model element (canvas substrate vocab)
   and renders it through a per-project language lens into a deterministic
   low-level code specification. Layer A of Phase 7's two-layer
   architecture; Layer B wraps these specs with scenario context.

   The lens contract — every registered `defmethod project` returns:

     {:projection-kind     <namespaced kw>   ; e.g. :clojure/type-to-malli
      :lens-id             <kw>              ; :clojure for fukan-on-fukan
      :model-element-kind  <kw>              ; :Type | :Affordance | :Module
      :model-element-id    <string>          ; canvas stable-id
      :target              {:path :namespace :symbol}
      :template            <string or nil>   ; rendered code; nil when prose-only
      :prose               <string>          ; semantic intent
      :context             <map>}            ; non-load-bearing extras

   Dispatch is two-level: Affordances route on `:canvas-role` (because
   function/getter/checker/invariant/rule/event/handler all share the
   `:Affordance` kind but project differently); Types and Modules route
   on the kind keyword directly. See `dispatch-key-of`.

   See DESIGN.md § \"Implementation linkage — the Clojure lens\" → The
   lens projection (Layer A) (original design:
   doc/plans/2026-05-27-project-lens-design.md, git history)."
  (:require [clojure.string :as str]))

(defn dispatch-key-of
  "Return the dispatch key for a model element.

   Affordances: use the :canvas-role (e.g. :canvas/event,
   :canvas/invariant, :canvas/rule, :canvas/operation,
   :fukan.canvas.monolith/exposed-call for canvas functions).

   Invariants (Phase 8 Sprint 5 — Option β): the synthetic dispatch
   key `:canvas/invariant+property-test` fires when the element carries
   `:canvas-role :canvas/invariant` AND no override flag selecting
   another projection-kind. The default flips invariants from the
   predicate-stub projection (`invariant-to-predicate`) to the
   `clojure.test.check` property-test projection
   (`invariant-to-property-test`). The element-map field
   `:canvas-projection-kind` carries the override when set; the legal
   values are `:property-test` (the default; routes to the synthetic
   key) and `:predicate` (the opt-out; falls through to plain
   `:canvas/invariant` for the predicate projection). The opt-out is
   reserved for a future canvas-side `(projects-to :predicate)`
   clause; today's canvas declarations get the property-test default.

   The narrow extension is invariant-specific by design: invariants are
   the only canvas role with two valid Clojure-side counterparts at
   present. Every other Phase 7 + 7.5 projection routes on the plain
   `:canvas-role` discriminator.

   Types: refine by :type-kind when provided so atomic values and
   record types route to distinct projections — `:Type/atomic` and
   `:Type/record`. A bare `:Type` (no :type-kind) falls back to the
   namespaceless `:Type` key for callers that don't (or can't) provide
   the discriminator yet.

   Modules: use the :model-element-kind directly.

   See DESIGN.md § \"Implementation linkage — the Clojure lens\" for the
   invariant+property-test dispatch (Option β design:
   doc/plans/2026-05-28-invariant-projection-design.md § 5, git history)."
  [{:keys [model-element-kind canvas-role type-kind canvas-projection-kind]}]
  (case model-element-kind
    :Affordance (if (and (= :canvas/invariant canvas-role)
                         ;; Default = property-test. Only :predicate falls
                         ;; through to the predicate projection.
                         (not= :predicate canvas-projection-kind))
                  :canvas/invariant+property-test
                  canvas-role)
    :Type       (cond
                  (= :atomic type-kind) :Type/atomic
                  (= :record type-kind) :Type/record
                  :else                  :Type)
    model-element-kind))

(defmulti project
  "Project a Model element through a registered lens. Dispatches on
   `[lens-id (dispatch-key-of element)]`. Each projection lives in a
   `fukan.canvas.project.<lens>.<name>` namespace that owns its
   `defmethod`. The substrate does no registration work itself."
  (fn [lens-id element _opts]
    [lens-id (dispatch-key-of element)]))

(defmethod project :default
  [lens-id element _opts]
  (throw (ex-info "no project-lens projection registered"
                  {:lens-id      lens-id
                   :dispatch-key (dispatch-key-of element)
                   :element      (select-keys element
                                              [:model-element-kind
                                               :canvas-role
                                               :stable-id])})))

(defn validate-projection
  "Return a vector of issue strings describing why m is not a valid
   projection. An empty vector means m satisfies the lens contract."
  [m]
  (let [issues (transient [])]
    (when-not (map? m)
      (conj! issues (str "projection must be a map; got " (pr-str (type m)))))
    (when (map? m)
      (when-not (qualified-keyword? (:projection-kind m))
        (conj! issues (str ":projection-kind must be a qualified keyword; got " (pr-str (:projection-kind m)))))
      (when-not (keyword? (:lens-id m))
        (conj! issues (str ":lens-id must be a keyword; got " (pr-str (:lens-id m)))))
      (when-not (keyword? (:model-element-kind m))
        (conj! issues (str ":model-element-kind must be a keyword; got " (pr-str (:model-element-kind m)))))
      (when-not (string? (:model-element-id m))
        (conj! issues (str ":model-element-id must be a string; got " (pr-str (:model-element-id m)))))
      (let [t (:target m)]
        (when-not (map? t)
          (conj! issues (str ":target must be a map; got " (pr-str t))))
        (when (map? t)
          (when-not (string? (:path t))
            (conj! issues (str ":target.path must be a string; got " (pr-str (:path t)))))
          (when-not (or (nil? (:namespace t)) (string? (:namespace t)))
            (conj! issues (str ":target.namespace must be a string or nil; got " (pr-str (:namespace t)))))
          (when-not (string? (:symbol t))
            (conj! issues (str ":target.symbol must be a string; got " (pr-str (:symbol t)))))))
      (when-not (or (nil? (:template m)) (string? (:template m)))
        (conj! issues (str ":template must be a string or nil; got " (pr-str (:template m)))))
      (when-not (string? (:prose m))
        (conj! issues (str ":prose must be a string; got " (pr-str (:prose m)))))
      (when (and (contains? m :context) (not (map? (:context m))))
        (conj! issues (str ":context, when present, must be a map; got " (pr-str (:context m))))))
    (persistent! issues)))

(defn valid-projection?
  "True iff m satisfies the projection contract."
  [m]
  (empty? (validate-projection m)))

;; ---------------------------------------------------------------------------
;; Shared compound-shape renderer
;;
;; Lives in core because multiple projections (`type-to-malli`,
;; `event-to-schema`, future variants) all render canvas compound shapes
;; into Malli expressions. The Phase 7 Sprint 1 design doc places the
;; helper at core-level rather than lens-localized so every lens-side
;; projection composes against the same table.

(def ^:private leaf-aliases
  "Canvas (PascalCase) → Malli (lowercase) leaf-keyword aliases for
   render-shape. Mirrors `inspect/drift.clj`'s table; kept in sync by
   the Phase 7 verification protocol."
  {:Integer  :int
   :String   :string
   :Boolean  :boolean
   :Float    :float
   :Double   :double
   :Long     :long
   :Keyword  :keyword
   :Symbol   :symbol
   :Map      :map
   :Vector   :vector
   :Set      :set
   :Any      :any
   :Unit     :any})

(defn render-shape
  "Render a canvas parsed shape map (the output of
   `fukan.canvas.core.shape/parse`) into a Malli expression.

   Atomic leaves are aliased through the Phase 6 table (`:Integer → :int`
   etc.); refs preserve their qualified-keyword identity so the
   implementing LLM's Malli registry can resolve them. Compound shapes
   render structurally per the Sprint 1 design doc table."
  [shape]
  (case (:kind shape)
    :atomic   (get leaf-aliases (:name shape) (:name shape))
    :ref      (:target shape)
    :optional [:maybe (render-shape (:inner shape))]
    :list     [:sequential (render-shape (:elem shape))]
    :set      [:set (render-shape (:elem shape))]
    :map      [:map-of (render-shape (:key shape)) (render-shape (:val shape))]
    :sum      (into [:or] (map render-shape (:variants shape)))
    :tuple    (into [:tuple] (map render-shape (:elems shape)))
    :record   (into [:map]
                    (map (fn [[n s]] [(if (keyword? n) n (keyword (name n)))
                                      (render-shape s)]))
                    (:fields shape))
    ;; Unknown shape — pass through best-effort leaf form so the caller
    ;; can still emit something a human (or LLM) will recognise.
    (or (:name shape) (:target shape) :any)))

(defn ns->path
  "Convention: dot→slash on namespace, hyphen→underscore on filename.
   Mirrors `inspect/drift.clj`'s ns->file-path."
  [ns-str]
  (when (string? ns-str)
    (str "src/"
         (-> ns-str
             (str/replace #"-" "_")
             (str/replace #"\." "/"))
         ".clj")))
