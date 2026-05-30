(ns fukan.canvas.core.classification
  "The classification stratum: a query-time, derived-classification surface over
   the Node+Relation+tagapp substrate.

   A node's kind/role is reified as a tag-application (`:tagapp/node` →
   `:tagapp/tag`). This namespace exposes that classification as a reusable
   query surface so consumers stop hand-reading the denormalised `:entity/type`
   / `:affordance/role` index. It is **tag-agnostic** — it hardcodes no kinds,
   reading only substrate schema — which is why it sits in `core/` and is usable
   by every tier above it (construct-kit, vocab, projection, lens, inspect).

   Everyday surface — what consumers reach for (rule + fn forms):

     `direct-kind` — a node's immediate classification tag: the value
                     `:affordance/role` carried for affordances, generalised to
                     every node (a Type's `:canvas/record`, a Module's
                     `:canvas/module`, …). Use for the exact kind/role.
     `family-of`   — the node's `:family/*` super-tag (single-valued given the
                     partition invariant). Use for \"what family / is this a
                     Module?\" — replaces `:entity/type`.
     `of-family`   — enumerate every node in a family. Use for \"all affordances\".

   Transitive foundation — the is-a layer the everyday surface is built on, kept
   for hierarchical vocabularies (DDD aggregate-root is-a entity, hexagonal
   ports/adapters …) but rarely called directly by consumers:

     `refines*`    — reflexive-transitive closure over `:tagdef/refines`.
     `kind-of`     — \"is-a, transitively\": a node is of kind ?k when its direct
                     tag refines* ?k. `family-of` is `kind-of` restricted to the
                     family root; everyday family questions should say `family-of`.
     `of-kind`     — enumerate a kind and its sub-kinds (the general form of
                     `of-family`).

   The refinement lattice is data: each tag-definition's `:refines` parent (or,
   when unset, the super-tag derived from its construction family) is projected
   as a `:tagdef/refines` datom by canvas-source. A second classification costs
   one `:refines` line and zero consumer changes — `(kind-of ?e :ddd/entity)`."
  (:require [datascript.core :as d]))

;; ── Family super-tags (the lattice roots) ────────────────────────────────────

(def family-super-tags
  "Construction family → its super-tag (the refinement-lattice root). A node's
   family is the `:family/*` ancestor its kind-tag refines, transitively."
  {:Affordance :family/affordance
   :Type       :family/type
   :Module     :family/module
   :State      :family/state})

(defn family->super-tag
  "The `:family/*` super-tag for a construction family keyword, or nil."
  [family]
  (get family-super-tags family))

(defn family-root?
  "True when `tag` is a family super-tag (namespace `family`)."
  [tag]
  (= "family" (some-> tag namespace)))

(def ^:private super-tag->element-kind
  "Inverse of family-super-tags: :family/* super-tag → legacy element-kind kw."
  (into {} (map (juxt val key)) family-super-tags))

;; ── Vocabulary projection (the refinement lattice as datoms) ─────────────────

(defn tagdef-datoms
  "Datoms projecting the tag-definition registry + the family super-tag roots
   into a substrate db, so refines*/kind-of/family-of close over the lattice.
   Each tag-definition's parent is its explicit `:refines` or the super-tag
   derived from its construction `:family`; the family super-tags are projected
   as parent-less roots. The registry is resolved at runtime — the vocabulary
   lives a tier up, but the substrate must carry it for the stratum to work, so
   this core fn reaches it dynamically rather than via a compile dependency."
  []
  (let [all-defs   ((requiring-resolve 'fukan.canvas.vocab.registry/all))
        registered (mapv (fn [{:keys [tag family payload doc refines]}]
                           (let [parent (or refines (family->super-tag family))]
                             (cond-> {:tagdef/tag tag}
                               payload (assoc :tagdef/payload payload)
                               family  (assoc :tagdef/family family)
                               parent  (assoc :tagdef/refines parent)
                               doc     (assoc :tagdef/doc doc))))
                         all-defs)
        roots      (mapv (fn [super-tag] {:tagdef/tag super-tag})
                         (vals family-super-tags))]
    (into roots registered)))

;; ── Datalog rules ──────────────────────────────────────────────────────────
;; Pass `rules` as the `%` input to d/q and reference the operators in :where:
;;   (d/q '[:find ?e :in $ % :where (kind-of ?e :family/affordance)] db rules)

(def rules
  "Datascript rule set for the classification stratum. Thread as the `%` input."
  '[[(direct-kind ?e ?tag)
     [?ta :tagapp/node ?e]
     [?ta :tagapp/tag ?tag]]
    ;; reflexive base: every registered tag (incl. family super-tags) refines* itself
    ;; (head vars must be distinct, so bind ?anc to ?t explicitly)
    [(refines* ?t ?anc)
     [?td :tagdef/tag ?t]
     [(identity ?t) ?anc]]
    ;; transitive step: ?t refines* ?anc when ?t's parent refines* ?anc
    [(refines* ?t ?anc)
     [?td :tagdef/tag ?t]
     [?td :tagdef/refines ?p]
     (refines* ?p ?anc)]
    ;; a node is of kind ?k when its direct tag refines* ?k
    [(kind-of ?e ?k)
     (direct-kind ?e ?t)
     (refines* ?t ?k)]
    ;; family-of: the node's :family/* ancestor
    [(family-of ?e ?fam)
     (kind-of ?e ?fam)
     [(namespace ?fam) ?ns]
     [(= ?ns "family")]]])

;; ── Per-entity lookup ────────────────────────────────────────────────────────

(defn direct-kind
  "The immediate classification tag of node `eid` (its tag-application tag), or
   nil when the node carries no tag-application. Single-valued: each node has
   one primary tag-application. Use the `direct-kind` rule (above) to embed the
   same relation inside a larger Datalog query."
  [db eid]
  (ffirst
   (d/q '[:find ?tag
          :in $ ?e
          :where [?ta :tagapp/node ?e]
                 [?ta :tagapp/tag ?tag]]
        db eid)))

(defn of-kind
  "Eids of every node whose immediate kind refines* `k` (transitively) — the
   general enumerator: a concrete/intermediate tag enumerates that kind and its
   sub-kinds. For the common family case, prefer `of-family`."
  [db k]
  (into [] (map first)
        (d/q '[:find ?e :in $ % ?k :where (kind-of ?e ?k)] db rules k)))

(defn of-family
  "Eids of every node in family super-tag `fam` (e.g. :family/affordance) — the
   everyday family enumerator. The family-restricted form of `of-kind`."
  [db fam]
  (of-kind db fam))

(defn family-of
  "The `:family/*` super-tag of node `eid`, or nil. Single-valued given the
   refinement partition invariant (see inspect.integrity/check-refinement).
   The fn form of the `family-of` rule, for per-entity lookup."
  [db eid]
  (ffirst
   (d/q '[:find ?fam :in $ % ?e :where (family-of ?e ?fam)] db rules eid)))

(defn element-kind
  "The legacy element-kind keyword (:Module/:Affordance/:Type/:State) of node
   `eid`, derived from its family super-tag, or nil. The model/Layer-A
   vocabulary projected from the stratum — lets addressing and projection keep
   their element-kind vocabulary while `:entity/type` is sourced from
   classification rather than stored."
  [db eid]
  (super-tag->element-kind (family-of db eid)))
