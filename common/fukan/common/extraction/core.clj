(ns fukan.common.extraction.core
  "The Clojure code-structure extractor — fukan parsing its OWN `src/`. The PL-specific half of the
   extraction seam: the only place that knows Clojure. It reads clj-kondo's `:analysis` output and
   maps Clojure constructs onto the code vocab:

     ns                      → Module      (a cohesion boundary)
     defn / defn- / defmulti → Operation   (a unit of computation — defmulti is a dispatch point)

   The extraction seam is its OWN area (`fukan.common.extraction/`), NOT part of the code vocabulary it
   populates: it mints no structures, it realizes the `fukan.model.extraction` plug-point. The
   Clojure-specific Operation mapping, effect classification, and Module assembly (the generic
   extracted-root wrapper) all live under `fukan.common.extraction.clojure.*`. This namespace is
   the shared orchestration — run clj-kondo, group, call the
   element builders → the engine-agnostic FACTS `{:roots :ground}`. The extractor OWNS no
   vocabulary — it EMITS instances by tag (the BUILD stamps provenance at the merge). It is the HOOK for the
   `fukan.model.extraction` plug-point; the composition root registers `extract-roots` as the fact
   extractor (the native Cozo build assembles the facts + calls the :ground closure). clj-kondo is
   the wheel we don't reinvent."
  (:require [clj-kondo.core :as kondo]
            [fukan.common.extraction.clojure.effect :as clj-effect]
            [fukan.common.extraction.clojure.module :as clj-module]
            [fukan.common.extraction.clojure.operation :as clj-operation]
            [fukan.common.vocab.code.module :as module]
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
   above the current max eid. Returns `cdb`. A `defmulti` extracts as an Operation, so calls through it
   and its method-body calls (re-homed by `attribute-defmethod-bodies`) resolve as ordinary `:calls`."
  [cdb var-usages]
  (let [op-eid  (into {} (map (fn [[ns name eid]] [[ns name] eid]))
                      (db/q cdb (str rules/eav module/in-module-cozo "
?[ns, name, eid] := structof[eid, 'fukan.common.vocab.code.operation/Operation'], extracted[eid],
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

(defn- attribute-defmethod-bodies
  "clj-kondo attributes a call inside a `defmethod` body to `:from-var nil` — a defmethod is not a named
   var, so its body calls have no enclosing var and `add-calls` drops them. Re-home each such body call
   onto the enclosing defmethod's MULTIMETHOD (which extracts as a PlugPoint): the caller becomes the
   dispatch point, so the plug-point's REACH — its satisfiers' calls, buried in inline method bodies —
   is captured (a body call re-homed onto its multimethod). A coarse stand-in for the satisfy side (until satisfiers
   are first-class), and the second half of what makes the see-through view whole. Rows are file-local,
   so this is per-file; a defmethod's body spans from its header row to the next top-level definition.
   Usages already carrying a `:from-var`, the defmethod headers themselves, and files with no defmethods
   pass through untouched."
  [var-definitions var-usages]
  (let [defs-by-file    (group-by :filename var-definitions)
        markers-by-file (->> var-usages (filter :defmethod) (group-by :filename))
        per-file        (into {}
                              (for [[file markers] markers-by-file
                                    :let [defrows (map :row (get defs-by-file file))
                                          bounds  (vec (sort (concat defrows (map :row markers))))
                                          m-at    (into {} (map (juxt :row identity)) markers)]]
                                [file {:bounds bounds :m-at m-at}]))]
    (mapv (fn [u]
            (if (or (:from-var u) (:defmethod u) (not (contains? per-file (:filename u))))
              u
              (let [{:keys [bounds m-at]} (per-file (:filename u))
                    enclosing (last (filter #(<= % (:row u)) bounds))
                    m         (get m-at enclosing)]
                (if m (assoc u :from-var (:name m) :from (:to m)) u))))
          var-usages)))

(defn extract-roots
  "The engine-agnostic extraction FACTS over the Clojure source under `paths`:
   `{:roots [[id InstanceValue]…] :ground (fn [cdb] …)}` — the Module/Operation roots (Operations
   stamped with DIRECT effects; a defmulti is a polymorphic Operation) plus a post-build `:ground`
   closure that grounds the `:calls` graph from the clj-kondo var-usages (defmethod-body calls re-homed
   onto their multimethod first). The native Cozo build assembles these facts onto the design graph,
   stamps stratum provenance at the merge (`stamp-stratum`), and calls `:ground` generically."
  [paths]
  (let [{:keys [namespace-definitions var-definitions var-usages]} (analyze paths)
        ops-by-ns    (group-by :ns (filter #(clj-operation/fn-defining (:defined-by %)) var-definitions))
        module-names (distinct (concat (map :name namespace-definitions)
                                       (keys ops-by-ns)))
        op-effs      (clj-effect/op-effects var-usages)
        attributed   (attribute-defmethod-bodies var-definitions var-usages)]
    {:roots      (vec (for [mname module-names
                            :let [ops (for [v (ops-by-ns mname)
                                            :let [effs (get op-effs [(str mname) (str (:name v))])]]
                                        (clj-operation/extract-operation v effs))]]
                        [(str mname) (clj-module/extract-module mname ops)]))
     :ground     (fn [cdb] (add-calls cdb attributed))}))
