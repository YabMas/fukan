(ns canvas.vocab.code.extractor
  "The Clojure code-structure extractor — fukan parsing its OWN `src/`. The PL-specific half of the
   extraction seam: the only place that knows Clojure. It reads clj-kondo's `:analysis` output and
   maps Clojure constructs onto the code vocab:

     ns                      → Module      (a cohesion boundary)
     defn / defn- / defmulti → Operation   (a unit of computation — defmulti is a dispatch point)

   The per-element mapping lives WITH each element (`operation/extract-operation`, `module/extract-module`,
   `effect/op-effects`); this namespace is the shared orchestration — run clj-kondo, group, call the
   element builders, assemble, then ground the actual call graph as `:calls`. The extractor OWNS no
   vocabulary — it EMITS instances by tag (stamping `:extracted true`). It is the HOOK for the
   `fukan.model.extraction` plug-point; the composition root registers `extract`. clj-kondo is the
   wheel we don't reinvent."
  (:require [clj-kondo.core :as kondo]
            [datascript.core :as d]
            [fukan.canvas.core.assemble :as assemble]
            [canvas.vocab.code.effect :as effect]
            [canvas.vocab.code.operation :as operation]
            [canvas.vocab.code.module :as module]))

(defn- analyze
  "Run clj-kondo over `paths` and return its `:analysis` — namespace + var
   definitions. Reads source (and writes clj-kondo's cache); deterministic output."
  [paths]
  (:analysis (kondo/run! {:lint (vec paths)
                          :config {:output {:analysis {:var-definitions {:meta true}
                                                       :var-usages true}}}})))

(defn- op-eid
  "Eid of the extracted Operation named `fn-str` that is a :child of the Module whose
   :entity/id is `ns-str`, or nil (callee is not one of our `defn`s)."
  [db ns-str fn-str]
  (ffirst (d/q '[:find ?op :in $ ?ns ?fn
                 :where [?m :structure/of :canvas.vocab.code.module/Module] [?m :entity/id ?ns]
                        [?r :rel/from ?m] [?r :rel/kind :child] [?r :rel/to ?op]
                        [?op :structure/of :canvas.vocab.code.operation/Operation] [?op :entity/name ?fn]]
               db ns-str fn-str)))

(defn- add-calls
  "Transact a `:calls` rel for every resolvable cross-op var-usage. Both endpoints must be
   extracted Operations we emitted; self-calls and core/library callees (unresolved) drop out.
   This is the FACTS layer — intent-free; the model<->design interpretation lives in the
   correspondence laws."
  [db var-usages]
  (let [pairs (->> var-usages
                   (keep (fn [{:keys [from from-var to name]}]
                           (when (and from-var to name)
                             (let [c (op-eid db (str from) (str from-var))
                                   e (op-eid db (str to)   (str name))]
                               (when (and c e (not= c e)) [c e])))))
                   distinct)]
    (d/db-with db (map-indexed (fn [n [c e]]
                                 {:rel/id   (str c "|calls|" n "|" e)
                                  :rel/from c :rel/kind :calls :rel/to e :rel/order n})
                               pairs))))

(defn extract
  "Extract a structure-db of Modules + the Operations they define from the Clojure source under
   `paths`, then ground the actual call graph as `:calls` rels. Operations are stamped with their
   DIRECT effects (`:performs`, logging excluded; `throw` as `:throws`); Modules are stamped
   `:val/extracted true` (provenance, like Operations). Orchestrates the per-element builders."
  [& paths]
  (let [{:keys [namespace-definitions var-definitions var-usages]} (analyze paths)
        ops-by-ns    (group-by :ns (filter #(operation/fn-defining (:defined-by %)) var-definitions))
        module-names (distinct (concat (map :name namespace-definitions)
                                       (keys ops-by-ns)))
        op-effs      (effect/op-effects var-usages)
        db (assemble/assemble-instances
            (for [mname module-names
                  :let [ops (for [v (ops-by-ns mname)
                                  :let [effs (get op-effs [(str mname) (str (:name v))])]]
                              (operation/extract-operation v effs))]]
              [(str mname) (module/extract-module mname ops)]))]
    (add-calls db var-usages)))
