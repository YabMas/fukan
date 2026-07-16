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
   the shared orchestration — run clj-kondo, group, call the element builders → the engine-agnostic
   FACTS `{:roots …}`. Every extracted entity is a FLAT root with a natural-key id (`\"ns\"` for a
   Module, `\"ns/op\"` for an Operation), and cross-references — a Module's `:child`, an Operation's
   `:calls` — are `substrate/Ref`s BY that id, so the ONE assembler links them exactly as it links
   authored var-refs (dedup by id, no post-build eid-arithmetic pass). The extractor OWNS no
   vocabulary — it EMITS instances by tag (the BUILD stamps provenance at the merge). It is the HOOK for
   the `fukan.model.extraction` plug-point; the composition root registers `extract-roots` as the fact
   extractor. clj-kondo is the wheel we don't reinvent."
  (:require [clj-kondo.core :as kondo]
            [fukan.common.extraction.clojure.effect :as clj-effect]
            [fukan.common.extraction.clojure.module :as clj-module]
            [fukan.common.extraction.clojure.operation :as clj-operation]))

(defn- analyze
  "Run clj-kondo over `paths` and return its `:analysis` — namespace + var
   definitions. Reads source (and writes clj-kondo's cache); deterministic output."
  [paths]
  (:analysis (kondo/run! {:lint (vec paths)
                          :config {:output {:analysis {:var-definitions {:meta true}
                                                       :var-usages true}}}})))

(defn- attribute-defmethod-bodies
  "clj-kondo attributes a call inside a `defmethod` body to `:from-var nil` — a defmethod is not a named
   var, so its body calls have no enclosing var and call-resolution drops them. Re-home each such body call
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

(defn- call-graph
  "The intra-project call graph as `{caller-op-id [callee-op-id…]}` over the natural-key op ids, from
   the (defmethod-attributed) clj-kondo var-usages. `key->id` maps a `[ns name]` pair to its op id (nil
   if the pair is not an extracted Operation); a usage is an edge iff BOTH endpoints resolve and differ
   (self-calls dropped). A `defmulti` extracts as an Operation, so calls through it and its method-body
   calls (re-homed onto their multimethod by `attribute-defmethod-bodies`) are ordinary edges."
  [attributed key->id]
  (->> attributed
       (keep (fn [{:keys [from from-var to name]}]
               (when (and from-var to name)
                 (let [c (key->id [(str from) (str from-var)])
                       e (key->id [(str to)   (str name)])]
                   (when (and c e (not= c e)) [c e])))))
       distinct
       (reduce (fn [m [c e]] (update m c (fnil conj []) e)) {})))

(defn extract-roots
  "The engine-agnostic extraction FACTS over the Clojure source under `paths`: `{:roots [[id
   InstanceValue]…]}`. Every extracted entity is a FLAT root with a natural-key id — `\"ns\"` for a
   Module, `\"ns/op\"` for an Operation — and cross-references are `substrate/Ref`s by that id: a
   Module's `:child` points at its op ids, an Operation's `:calls` at its callees' op ids (resolved by
   `call-graph`). So the native Cozo build's ONE assembler links the call graph the same way it links
   authored var-refs (dedup by id), with no post-build eid-arithmetic pass. Operations carry their
   DIRECT effects; a defmulti is a polymorphic Operation. The build stamps stratum provenance at the
   merge (`stamp-stratum`)."
  [paths]
  (let [{:keys [namespace-definitions var-definitions var-usages]} (analyze paths)
        ops-by-ns    (group-by :ns (filter #(clj-operation/fn-defining (:defined-by %)) var-definitions))
        module-names (distinct (concat (map :name namespace-definitions)
                                       (keys ops-by-ns)))
        op-effs      (clj-effect/op-effects var-usages)
        attributed   (attribute-defmethod-bodies var-definitions var-usages)
        op-id        (fn [mname nm] (str mname "/" nm))
        by-key       (into {} (for [[mname vs] ops-by-ns, v vs]
                                [[(str mname) (str (:name v))] (op-id mname (:name v))]))
        calls        (call-graph attributed by-key)
        per-module   (for [mname module-names
                           :let [op-roots (for [v (ops-by-ns mname)
                                                :let [oid  (op-id mname (:name v))
                                                      effs (get op-effs [(str mname) (str (:name v))])]]
                                            [oid (clj-operation/extract-operation v effs (get calls oid))])]]
                       {:module [(str mname) (clj-module/extract-module mname (mapv first op-roots))]
                        :ops    op-roots})]
    {:roots (vec (concat (map :module per-module)
                         (mapcat :ops per-module)))}))
