(ns fukan.canvas.lens.consistency
  "Consistency lens — structural lens; surfaces three flavours of drift
   the LLM should weigh:

     1. Naming-style mixing within a {module, entity-type, affordance-role}
        partition. PascalCase records + snake_case functions is expected
        canvas idiom; the partition narrows surfacing to true outliers.

     2. Field-name → field-type drift across record-shaped Types. When the
        same field-name appears with two or more distinct target types,
        the lens surfaces the divergence as a normalisation candidate.

     3. Sister-module structural asymmetry. Modules sharing a name prefix
        (`validation.rules-4a`, `rules-4b`, …) typically expose parallel
        content. The lens groups siblings, computes each member's
        {entity-type, affordance-role} multiset, and surfaces members
        whose multiset differs from the group majority.

   Output is observational, not prescriptive. Each finding is a question
   for the LLM to weigh against context, not an error to auto-fix.

   Full design in git history:
   doc/plans/2026-05-26-feedback-signals-design.md § 4. Consistency — WEIGH tier."
  (:require [clojure.string :as str]
            [datascript.core :as d]
            [fukan.canvas.core.classification :as classification]))

;; ---------------------------------------------------------------------------
;; Defaults + opts
;; ---------------------------------------------------------------------------

(def ^:private all-checks #{:naming :field-types :sisters})

(def ^:private default-min-cluster 3)

(def ^:private default-exempt-fields
  "Field names that are known to legitimately carry different types across
   contexts. Treated as exempt by the field-type-drift check unless the
   caller overrides via `opts`."
  #{:value :data :payload})

(defn- opts->checks [opts]
  (or (:checks opts) all-checks))

(defn- opts->min-cluster [opts]
  (or (:min-cluster opts) default-min-cluster))

(defn- opts->exempt-fields [opts]
  (or (:exempt-fields opts) default-exempt-fields))

;; ---------------------------------------------------------------------------
;; Naming-style classifier
;; ---------------------------------------------------------------------------

(defn classify-style
  "Classify a name string into one of:
     :snake_case    — lowercase letters/digits with underscores
     :camelCase     — starts lowercase, contains an internal uppercase
     :PascalCase    — starts uppercase (no underscores, not all-upper)
     :kebab-case    — lowercase letters/digits with hyphens
     :SHOUTING_CASE — all uppercase (with optional underscores)
     :other         — anything else

   Order of checks matters: SHOUTING_CASE is tested before snake_case
   because uppercase-with-underscores would otherwise fall through to
   neither. Single-segment lowercase tokens (`port`, `model`) classify
   as :snake_case for convenience — they have no style conflict with
   the snake-case bucket."
  [n]
  (let [s (str n)]
    (cond
      (str/blank? s) :other
      (re-matches #"[A-Z][A-Z0-9_]*" s) :SHOUTING_CASE
      (re-matches #"[a-z][a-z0-9_]*"  s) :snake_case
      (re-matches #"[a-z][a-z0-9-]*"  s) :kebab-case
      (re-matches #"[A-Z][A-Za-z0-9]*" s) :PascalCase
      (re-matches #"[a-z][A-Za-z0-9]*" s) :camelCase
      :else :other)))

;; ---------------------------------------------------------------------------
;; Datascript query helpers
;; ---------------------------------------------------------------------------

(defn- all-modules
  "Return seq of {:eid :name} for every Module."
  [db]
  (->> (d/q '[:find ?e ?n
              :where [?e :entity/type :Module]
                     [?e :entity/name ?n]]
            db)
       (map (fn [[e n]] {:eid e :name n}))))

(defn- children-of
  "Return seq of {:eid :name :entity-type :role} for the direct children
   of a module (by module eid). :role is present only on Affordances."
  [db module-eid]
  (->> (d/q '[:find ?c ?cn ?ct
              :in $ ?m
              :where [?m :module/child ?c]
                     [?c :entity/name ?cn]
                     [?c :entity/type ?ct]]
            db module-eid)
       (map (fn [[c cn ct]]
              (let [role (when (= :Affordance ct)
                           (classification/direct-kind db c))]
                {:eid c :name cn :entity-type ct :role role})))))

;; ---------------------------------------------------------------------------
;; Check 1 — naming mixing within {module, entity-type, role}
;; ---------------------------------------------------------------------------

(defn- naming-mixings
  "For each {module, entity-type, role} partition with two or more distinct
   naming styles, emit a finding listing the styles → names map."
  [db]
  (let [rows (for [{mname :name meid :eid} (all-modules db)
                   {:keys [name entity-type role]} (children-of db meid)]
               {:module mname
                :entity-type entity-type
                :role role
                :name name
                :style (classify-style name)})
        partitions (group-by (juxt :module :entity-type :role) rows)]
    (->> partitions
         (keep (fn [[[mname etype role] members]]
                 (let [by-style (->> members
                                     (group-by :style)
                                     (reduce-kv (fn [acc style ms]
                                                  (assoc acc style
                                                         (vec (sort (map :name ms)))))
                                                {}))]
                   (when (>= (count by-style) 2)
                     {:module mname
                      :entity-type etype
                      :role role
                      :styles by-style}))))
         (sort-by (juxt :module #(pr-str (:entity-type %)) #(pr-str (:role %))))
         vec)))

;; ---------------------------------------------------------------------------
;; Check 2 — field-name → field-type drift across records
;; ---------------------------------------------------------------------------

(defn- type-name-of [db eid]
  (ffirst (d/q '[:find ?n :in $ ?e :where [?e :entity/name ?n]] db eid)))

(defn- module-name-for-entity [db eid]
  (ffirst (d/q '[:find ?mn
                 :in $ ?c
                 :where [?m :module/child ?c]
                        [?m :entity/name ?mn]]
               db eid)))

(defn- field-type-drifts
  "Group all (type, field-name, field-type) tuples by field-name; for any
   field-name whose set of associated field-types has more than one
   element (after removing exempt-fields), emit a finding.

   `:type/fields` is a cardinality-many attribute whose value is a
   `[field-name field-type]` 2-vec; we walk the AEVT datoms rather than
   query so the access pattern is identical to other inspectors in the
   substrate (`inspect/coverage.clj`)."
  [db exempt]
  (let [rows (->> (d/datoms db :aevt :type/fields)
                  (keep (fn [d]
                          (let [v (.-v d)]
                            (when (and (vector? v) (= 2 (count v)))
                              [(.-e d) (first v) (second v)])))))
        by-field (group-by second rows)]
    (->> by-field
         (keep (fn [[fname tuples]]
                 (when-not (contains? exempt fname)
                   (let [by-type (->> tuples
                                      (group-by #(nth % 2))
                                      (reduce-kv
                                        (fn [acc ftype ts]
                                          (assoc acc ftype
                                                 (vec (sort
                                                        (for [[eid _ _] ts]
                                                          (let [mn (module-name-for-entity db eid)
                                                                tn (type-name-of db eid)]
                                                            (str (or mn "?") "/type/"
                                                                 (or tn "?")
                                                                 "/" (name fname))))))))
                                        {}))]
                     (when (>= (count by-type) 2)
                       {:field-name fname
                        :types by-type})))))
         (sort-by (comp pr-str :field-name))
         vec)))

;; ---------------------------------------------------------------------------
;; Check 3 — sister-module structural symmetry
;; ---------------------------------------------------------------------------

(defn- module-prefix
  "Strip the trailing segment from a module name and return the resulting
   prefix. Segments are split on either `.` or `-` so families like
   `rules-4a`/`rules-4b` and `validation.rules-4a`/`validation.rules-4b`
   both group. Returns nil if the name has no separator."
  [mname]
  (let [s (str mname)
        idx-dot   (.lastIndexOf s ".")
        idx-dash  (.lastIndexOf s "-")
        idx       (max idx-dot idx-dash)]
    (when (pos? idx)
      (subs s 0 (inc idx)))))

(defn- multiset-of
  "Return {[entity-type role] count} for a module's direct children."
  [db module-eid]
  (->> (children-of db module-eid)
       (map (fn [{:keys [entity-type role]}] [entity-type role]))
       frequencies))

(defn- majority-multiset
  "Pick the most-frequent multiset across a group. Ties are broken by the
   multiset whose own size (sum of values) is largest; if still tied, the
   first encountered wins. Returns the multiset map."
  [multisets]
  (->> multisets
       frequencies
       (sort-by (fn [[ms cnt]] [(- cnt) (- (reduce + (vals ms)))]))
       ffirst))

(defn- multiset-diff
  "Return {:extras …} where extras is the multiset of (k, count) entries
   present in ms but missing/lower in majority. (Missing entries are also
   surfaced — the count goes to the difference, which is positive.)"
  [ms majority]
  (reduce-kv
    (fn [acc k cnt]
      (let [m-cnt (get majority k 0)
            diff  (- cnt m-cnt)]
        (cond-> acc
          (pos? diff) (assoc k diff))))
    {} ms))

(defn- sister-asymmetries
  [db min-cluster]
  (let [modules  (all-modules db)
        by-pref  (->> modules
                      (keep (fn [{:keys [name eid]}]
                              (when-let [p (module-prefix name)]
                                {:prefix p :name name :eid eid})))
                      (group-by :prefix))]
    (->> by-pref
         (keep (fn [[pref siblings]]
                 (when (>= (count siblings) min-cluster)
                   (let [member->ms (into {}
                                          (map (fn [{:keys [name eid]}]
                                                 [name (multiset-of db eid)])
                                               siblings))
                         all-ms     (vals member->ms)
                         majority   (majority-multiset all-ms)
                         outliers   (->> member->ms
                                         (keep (fn [[mname ms]]
                                                 (when (not= ms majority)
                                                   (let [extras  (multiset-diff ms majority)
                                                         missing (multiset-diff majority ms)]
                                                     (when (or (seq extras) (seq missing))
                                                       (cond-> {:module mname}
                                                         (seq extras)  (assoc :extras extras)
                                                         (seq missing) (assoc :missing missing)))))))
                                         (sort-by :module)
                                         vec)]
                     (when (seq outliers)
                       {:prefix    pref
                        :majority  majority
                        :outliers  outliers})))))
         (sort-by :prefix)
         vec)))

;; ---------------------------------------------------------------------------
;; Compute
;; ---------------------------------------------------------------------------

(defn compute
  "Compute the consistency findings against `canvas-db`. `opts`:

     :checks         #{:naming :field-types :sisters}  ; default: all three
     :min-cluster    <int>                             ; default: 3
     :exempt-fields  #{:value :data :payload}          ; default: as shown

   Returns:
     {:naming-mixings     [...]
      :field-type-drifts  [...]
      :sister-asymmetries [...]}

   Sections corresponding to disabled checks are returned as empty
   vectors — callers can rely on the shape being stable."
  [canvas-db opts]
  (let [checks (opts->checks opts)
        min-c  (opts->min-cluster opts)
        exempt (opts->exempt-fields opts)]
    {:naming-mixings     (if (contains? checks :naming)
                           (naming-mixings canvas-db) [])
     :field-type-drifts  (if (contains? checks :field-types)
                           (field-type-drifts canvas-db exempt) [])
     :sister-asymmetries (if (contains? checks :sisters)
                           (sister-asymmetries canvas-db min-c) [])}))

;; ---------------------------------------------------------------------------
;; Render
;; ---------------------------------------------------------------------------

(defn- render-naming-section [mixings]
  (if (empty? mixings)
    "_no naming-style mixings within a single {module, type, role} partition._"
    (->> mixings
         (map (fn [{:keys [module entity-type role styles]}]
                (let [hdr (str "- **" module "** · " (pr-str entity-type)
                               (when role (str " · " (pr-str role))))
                      lines (->> styles
                                 (sort-by key)
                                 (map (fn [[style names]]
                                        (str "    - " (name style) ": "
                                             (str/join ", " names)))))]
                  (str hdr "\n" (str/join "\n" lines)))))
         (str/join "\n"))))

(defn- render-field-section [drifts]
  (if (empty? drifts)
    "_no field-name → field-type drift across records (after exemptions)._"
    (->> drifts
         (map (fn [{:keys [field-name types]}]
                (let [hdr (str "- **" field-name "** appears with "
                               (count types) " distinct types:")
                      lines (->> types
                                 (sort-by key)
                                 (map (fn [[ftype occurrences]]
                                        (str "    - " (pr-str ftype) ": "
                                             (str/join ", " occurrences)))))]
                  (str hdr "\n" (str/join "\n" lines)))))
         (str/join "\n"))))

(defn- render-sister-section [groups]
  (if (empty? groups)
    "_no sister-module structural asymmetries detected._"
    (->> groups
         (map (fn [{:keys [prefix majority outliers]}]
                (let [hdr (str "- **prefix `" prefix "`** — majority shape: "
                               (pr-str majority))
                      olines (->> outliers
                                  (map (fn [{:keys [module extras missing]}]
                                         (str "    - `" module "`"
                                              (when (seq extras)
                                                (str " extras=" (pr-str extras)))
                                              (when (seq missing)
                                                (str " missing=" (pr-str missing)))))))]
                  (str hdr "\n" (str/join "\n" olines)))))
         (str/join "\n"))))

(defn render
  "Render compute output as markdown. `findings` is the map returned by
   `compute` (or nil); `opts` is unused but kept for signature symmetry."
  [findings _opts]
  (let [{:keys [naming-mixings field-type-drifts sister-asymmetries]
         :or   {naming-mixings [] field-type-drifts [] sister-asymmetries []}}
        (or findings {})
        clean? (and (empty? naming-mixings)
                    (empty? field-type-drifts)
                    (empty? sister-asymmetries))]
    (if clean?
      "No consistency findings — all three checks clean."
      (str "### Naming-style mixing (within {module, entity-type, role})\n\n"
           (render-naming-section naming-mixings) "\n\n"
           "### Field-name → field-type drift\n\n"
           (render-field-section field-type-drifts) "\n\n"
           "### Sister-module structural asymmetry\n\n"
           (render-sister-section sister-asymmetries)))))

;; ---------------------------------------------------------------------------
;; The lens
;; ---------------------------------------------------------------------------

(def lens
  {:id              :consistency
   :description     "Consistency — naming style, field-type drift, sister-module symmetry"
   :prompt-fragment
   (str "Consistency surfaces three kinds of structural drift the LLM "
        "should WEIGH (not auto-fix):\n\n"
        "1. **Naming-style mixing** within a {module, entity-type, role} "
        "partition. Records are typically `PascalCase`; functions are "
        "typically `snake_case`; the partition is narrowed so cross-type "
        "style variation isn't surfaced. A mix WITHIN a single role is "
        "the real signal.\n\n"
        "2. **Field-name → field-type drift.** Records sharing a field "
        "name but disagreeing on its type are candidate normalisations. "
        "Some fields legitimately vary (`:value`, `:data`, `:payload`) — "
        "use the `:exempt-fields` opt for known cases.\n\n"
        "3. **Sister-module asymmetry.** Groups of >= 3 modules sharing "
        "a name prefix often parallel each other intentionally; an "
        "outlier whose entity-type/role multiset differs from the "
        "majority may be a deliberate richer member or a porting "
        "leftover.\n\n"
        "Each finding is an observation. Weigh: intentional asymmetry, "
        "or canvas-content fix?")
   :compute compute
   :render  render})
