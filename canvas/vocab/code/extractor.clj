(ns canvas.vocab.code.extractor
  "The Clojure code-structure extractor — fukan parsing its OWN `src/`. The PL-specific half of the
   extraction seam: the only place that knows Clojure. It reads clj-kondo's `:analysis` output and
   maps Clojure constructs onto the code vocab:

     ns                      → Module      (a cohesion boundary)
     defn / defn- / defmulti → Operation   (a unit of computation — defmulti is a dispatch point)

   The per-element mapping lives WITH each element (`operation/extract-operation`, `module/extract-module`,
   `effect/op-effects`); this namespace is the shared orchestration — run clj-kondo, group, call the
   element builders → the engine-agnostic FACTS `{:roots :ground}`. The extractor OWNS no
   vocabulary — it EMITS instances by tag (stamping `:extracted true`). It is the HOOK for the
   `fukan.model.extraction` plug-point; the composition root registers `extract-roots` as the fact
   extractor (the native Cozo build assembles the facts + calls the :ground closure). clj-kondo is
   the wheel we don't reinvent."
  (:require [clj-kondo.core :as kondo]
            [canvas.vocab.code.effect :as effect]
            [canvas.vocab.code.operation :as operation]
            [canvas.vocab.code.module :as module]
            [fukan.cozo.db :as db]
            [fukan.cozo.rules :as rules]))

(defn- analyze
  "Run clj-kondo over `paths` and return its `:analysis` — namespace + var
   definitions. Reads source (and writes clj-kondo's cache); deterministic output."
  [paths]
  (:analysis (kondo/run! {:lint (vec paths)
                          :config {:output {:analysis {:var-definitions {:meta true}
                                                       :var-usages true}}}})))

(defn- add-calls
  "Ground the actual call graph as `:calls` rels in `cdb` (the POST-BUILD grounding the native build's
   `:ground` hook runs). Resolves each var-usage's caller/callee to the eid of the extracted Operation
   named `fn` in module `ns` (a single cozo query `{[ns name] → eid}`), then inserts the `:calls` rels
   above the current max eid. Returns `cdb`."
  [cdb var-usages]
  (let [op-eid  (into {} (map (fn [[ns name eid]] [[ns name] eid]))
                      (db/q cdb (str rules/eav "
?[ns, name, eid] := structof[eid, 'canvas.vocab.code.operation/Operation'], extracted[eid],
                   ename[eid, name], in_module[eid, ns]")))
        max-eid (ffirst (db/q cdb "alle[e] := *t_int[e, _, _]
alle[e] := *t_str[e, _, _]
alle[e] := *t_bool[e, _, _]
?[max(e)] := alle[e]"))
        pairs   (->> var-usages
                     (keep (fn [{:keys [from from-var to name]}]
                             (when (and from-var to name)
                               (let [c (op-eid [(str from) (str from-var)])
                                     e (op-eid [(str to)   (str name)])]
                                 (when (and c e (not= c e)) [c e])))))
                     distinct vec)
        int-rows (vec (mapcat (fn [n [c e]]
                                (let [rid (+ max-eid 1 n)]
                                  [[rid "rel/from" c] [rid "rel/to" e] [rid "rel/order" n]]))
                              (range) pairs))
        str-rows (vec (map-indexed (fn [n _] [(+ max-eid 1 n) "rel/kind" "calls"]) pairs))]
    (when (seq pairs)
      (db/q cdb "?[e, a, v] <- $rows :put t_int {e, a, v}" {:rows int-rows})
      (db/q cdb "?[e, a, v] <- $rows :put t_str {e, a, v}" {:rows str-rows}))
    cdb))

(defn extract-roots
  "The engine-agnostic extraction FACTS over the Clojure source under `paths`:
   `{:roots [[id InstanceValue]…] :ground (fn [cdb] …)}` — the Module/Operation roots
   (Operations stamped with DIRECT effects; Modules `:val/extracted true`) plus a post-build
   `:ground` closure that grounds the `:calls` graph from the clj-kondo var-usages. The native
   Cozo build assembles these facts onto the design graph and calls `:ground` generically."
  [paths]
  (let [{:keys [namespace-definitions var-definitions var-usages]} (analyze paths)
        ops-by-ns    (group-by :ns (filter #(operation/fn-defining (:defined-by %)) var-definitions))
        module-names (distinct (concat (map :name namespace-definitions)
                                       (keys ops-by-ns)))
        op-effs      (effect/op-effects var-usages)]
    {:roots      (vec (for [mname module-names
                            :let [ops (for [v (ops-by-ns mname)
                                            :let [effs (get op-effs [(str mname) (str (:name v))])]]
                                        (operation/extract-operation v effs))]]
                        [(str mname) (module/extract-module mname ops)]))
     :ground     (fn [cdb] (add-calls cdb var-usages))}))
