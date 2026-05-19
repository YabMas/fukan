(ns fukan.validation.rules-4a
  "Phase 4a — composition rules (per DESIGN.md §4a).

   Five rules:
     1. Each module-Container has at most one composite parent (error).
     2. Modules referenced by no `.boundary` are top-level (warning).
     3. No cycles in subsystem composition (error).
     4. Subsystem `contains:` paths must reference known modules / subsystems
        — unresolved paths are errors.
     5. Subsystem names are unique within the composition (error).

   Identification (per Plan 2b / Plan 3b Task 7):
     module-Container          = primitive carrying `Allium::Module` tag.
     composite (Subsystem)     = primitive carrying `Boundary::Subsystem` tag.
     subsystem name            = composite Container's `:label` field.
     subsystem children        = composite Container's `:children` set (ids)."
  (:require [clojure.set :as set]
            [fukan.validation.violation :as v]
            [fukan.model.build :as build]))

(defn- tag-applications-of
  "All TagApplications on `model` whose tag matches (namespace, name)."
  [model namespace tag-name]
  (filter (fn [ta]
            (and (= namespace (-> ta :tag :namespace))
                 (= tag-name  (-> ta :tag :name))))
          (:tag-apps model)))

(defn- module-ids [model]
  (set (map (comp :id :target)
            (tag-applications-of model "Allium" "Module"))))

(defn- subsystem-ids [model]
  (set (map (comp :id :target)
            (tag-applications-of model "Boundary" "Subsystem"))))

(defn- subsystem-by-id [model]
  (into {} (map (fn [id] [id (build/get-primitive model id)]))
        (subsystem-ids model)))

(defn- children-of [container]
  (set (:children container)))

;; -- Rule 1: at most one composite parent ------------------------------------

(defn- multiple-parents [model]
  (let [subs    (subsystem-by-id model)
        parents (reduce-kv
                  (fn [acc sub-id sub]
                    (reduce (fn [a child]
                              (update a child (fnil conj #{}) sub-id))
                            acc (children-of sub)))
                  {} subs)]
    (for [[child ps] parents
          :when (> (count ps) 1)]
      (v/make-violation
        {:severity :error :phase :phase4 :sub-phase :4a
         :kind     :4a/multiple-composite-parents
         :location {:module child :parents (vec ps)}
         :message  (str "module " child " has multiple composite parents: "
                        (vec ps))}))))

;; -- Rule 2: top-level modules (warning) -------------------------------------

(defn- top-level-modules [model]
  (let [subs         (subsystem-by-id model)
        all-children (reduce (fn [acc sub] (into acc (children-of sub)))
                             #{} (vals subs))
        modules      (module-ids model)
        top-level    (reduce disj modules all-children)]
    (for [m top-level]
      (v/make-violation
        {:severity :warning :phase :phase4 :sub-phase :4a
         :kind     :4a/top-level-module
         :location {:module m}
         :message  (str "module " m
                        " is not contained by any subsystem (top-level)")}))))

;; -- Rule 3: no cycles in subsystem composition ------------------------------

(defn- subsystem-cycles
  "DFS from each subsystem; if we re-visit our starting node along the path,
   record a cycle. The `visiting` set guards us from looping forever in
   already-visited (non-starting) sub-graphs."
  [model]
  (let [subs (subsystem-by-id model)
        cycle-from
        (fn cycle-from [start path visiting]
          (let [sub  (subs (peek path))
                kids (filter subs (children-of sub))]
            (some (fn [k]
                    (cond
                      (= k start)    (conj path k)
                      (visiting k)   nil
                      :else (cycle-from start (conj path k) (conj visiting k))))
                  kids)))]
    (for [id (keys subs)
          :let [cyc (cycle-from id [id] #{id})]
          :when cyc]
      (v/make-violation
        {:severity :error :phase :phase4 :sub-phase :4a
         :kind     :4a/subsystem-cycle
         :location {:subsystem id :path cyc}
         :message  (str "subsystem composition cycle detected starting at "
                        id)}))))

;; -- Rule 4: contains: paths must resolve ------------------------------------

(defn- unresolved-contains [model]
  (let [subs  (subsystem-by-id model)
        known (set/union (module-ids model) (subsystem-ids model))]
    (for [[sub-id sub] subs
          child (children-of sub)
          :when (not (contains? known child))]
      (v/make-violation
        {:severity :error :phase :phase4 :sub-phase :4a
         :kind     :4a/unresolved-contains
         :location {:subsystem sub-id :child child}
         :message  (str "subsystem " sub-id " contains: " child
                        " which is not a known module or subsystem")}))))

;; -- Rule 5: unique subsystem names ------------------------------------------

(defn- duplicate-subsystem-names [model]
  (let [subs    (vals (subsystem-by-id model))
        by-name (group-by :label subs)]
    (for [[name group] by-name
          :when (> (count group) 1)]
      (v/make-violation
        {:severity :error :phase :phase4 :sub-phase :4a
         :kind     :4a/duplicate-subsystem-name
         :location {:name name :subsystems (mapv :id group)}
         :message  (str "subsystem name " name
                        " is used by multiple composites: "
                        (mapv :id group))}))))

(defn check
  "Run all 4a composition rules. Returns a vector of Violations."
  [model]
  (vec (concat
         (multiple-parents model)
         (top-level-modules model)
         (subsystem-cycles model)
         (unresolved-contains model)
         (duplicate-subsystem-names model))))
