(ns fukan.canvas.vocab.construct
  "The construct-kit interpreter: registry-driven node construction.

   `build` takes a tag, name, canonical payload, and a `forms` map of edge
   sources, reads the tag-definition (family, payload type, :edges directives),
   and produces the node + its edges generically — the path the bespoke lifts
   collapse into. Kind-agnostic: it interprets tag-definitions as data and
   hardcodes no kinds.

   Edge strategies (the production-directive vocabulary):
     :shape-refs   — emit `:edge` for every :ref target in the node's shape
     :to-keywords  — emit `:edge` to each keyword drawn from form `:from`
     :by-name      — resolve each name from form `:from` to an entity in the
                     enclosing module (optional `:role` filter) and emit `:edge`

   `forms` maps an edge-source name to a seq of refs; each ref is a keyword/name
   or a one-element form like `(EventName)` (its `first` is the ref). Single
   forms are wrapped in a one-element seq by the caller; repeatable forms pass
   their seq-of-arg-seqs directly."
  (:require [datascript.core :as d]
            [fukan.canvas.core.helpers :as h]
            [fukan.canvas.core.substrate :as sub]
            [fukan.canvas.vocab.registry :as registry]))

(def ^:private by-tag
  (into {} (map (juxt :tag identity)) registry/tag-definitions))

;; ── payload placement ─────────────────────────────────────────────────────

(defn- node-attrs
  "Node field map for a tag + canonical payload value, placed per
   (family, payload-type) from the tag-definition."
  [tag name payload doc returns-label]
  (let [{:keys [family] ptype :payload} (by-tag tag)
        base (cond-> {:name name}
               doc                    (assoc :doc doc)
               returns-label          (assoc :returns-label returns-label)
               (= family :Affordance) (assoc :node-kind :Affordance :role tag)
               (= family :Type)       (assoc :node-kind :Type))]
    (case [family ptype]
      [:Type :record]         (assoc base :type-kind :record :fields (:fields payload))
      [:Type :none]           (assoc base :type-kind :atomic)
      [:Affordance :arrow]    (assoc base :shape payload)
      [:Affordance :record]   (assoc base :shape payload)
      [:Affordance :prose]    (assoc base :formal-expression payload)
      [:Affordance :trigger]  (assoc base :formal-expression payload)
      [:Affordance :on-emits] (assoc base :formal-expression payload)
      base)))

(defn- node-shape
  "The shape value to walk for :shape-refs — the canonical payload when it is a
   shape (arrow/record), else nil."
  [tag payload]
  (when (#{:arrow :record} (:payload (by-tag tag))) payload))

;; ── edge strategies ───────────────────────────────────────────────────────

(defn- shape-ref-targets
  "All :ref targets reachable in a parsed shape."
  [shape]
  (case (:kind shape)
    :ref      [(:target shape)]
    :optional (shape-ref-targets (:inner shape))
    :list     (shape-ref-targets (:elem shape))
    :set      (shape-ref-targets (:elem shape))
    :map      (concat (shape-ref-targets (:key shape)) (shape-ref-targets (:val shape)))
    :sum      (mapcat shape-ref-targets (:variants shape))
    :tuple    (mapcat shape-ref-targets (:elems shape))
    :record   (mapcat (fn [[_ s]] (shape-ref-targets s)) (:fields shape))
    :arrow    (concat (shape-ref-targets (:inputs shape)) (shape-ref-targets (:outputs shape)))
    []))

(defn- ref-of [v] (if (sequential? v) (first v) v))

(defn- resolve-in-module
  "Eid of the entity named `nm` in the enclosing module (optionally filtered to
   `:affordance/role` = role), or nil."
  [nm role]
  (let [db @h/*store* mid h/*enclosing-module*]
    (when mid
      (ffirst
       (if role
         (d/q '[:find ?id :in $ ?mid ?n ?role
                :where [?m :entity/id ?mid] [?m :module/child ?a]
                       [?a :entity/name ?n] [?a :affordance/role ?role] [?a :entity/id ?id]]
              db mid nm role)
         (d/q '[:find ?id :in $ ?mid ?n
                :where [?m :entity/id ?mid] [?m :module/child ?a]
                       [?a :entity/name ?n] [?a :entity/id ?id]]
              db mid nm))))))

(defn- emit-shape-refs! [from-id shape edge]
  (doseq [t (shape-ref-targets shape)]
    (h/declare-relation from-id edge t)))

(defn- emit-to-keywords! [from-id forms {:keys [from edge]}]
  (doseq [v (get forms from)]
    (let [kw (ref-of v)]
      (when (keyword? kw)
        (h/declare-relation from-id edge kw)))))

(defn- emit-by-name! [from-id forms {:keys [from edge role]}]
  (doseq [v (get forms from)]
    (let [nm  (str (ref-of v))
          eid (resolve-in-module nm role)]
      (if eid
        (h/declare-relation from-id edge eid)
        (binding [*out* *err*]
          (println (str "canvas/construct: (" (clojure.core/name from) " " nm
                        ") — not found in enclosing module, skipping " edge " Relation")))))))

;; ── build ─────────────────────────────────────────────────────────────────

(defn build
  "Build a node for `tag` with canonical `payload`, registering it as a child of
   the enclosing container and emitting the tag-definition's :edges from `forms`.
   Returns the node."
  [tag name payload forms & {:keys [doc returns-label]}]
  (let [node    (h/declare-node (sub/node (node-attrs tag name payload doc returns-label)))
        from-id (sub/id-of node)
        shape   (node-shape tag payload)]
    (doseq [{:keys [strategy] :as dir} (:edges (by-tag tag))]
      (case strategy
        :shape-refs  (emit-shape-refs! from-id shape (:edge dir))
        :to-keywords (emit-to-keywords! from-id forms dir)
        :by-name     (emit-by-name! from-id forms dir)))
    node))
