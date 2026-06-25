(ns canvas.vocab.code.extractor
  "The Clojure code-structure extractor — fukan parsing its OWN `src/`. The PL-specific half of the
   extraction seam: the only place that knows Clojure. It reads clj-kondo's `:analysis` output and
   maps Clojure constructs onto the code vocab:

     ns                      → Module      (a cohesion boundary)
     defn / defn- / defmulti → Operation   (a unit of computation — defmulti is a dispatch point)

   The per-element mapping lives WITH each element (`operation/extract-operation`, `module/extract-module`,
   `effect/op-effects`); this namespace is the shared orchestration — run clj-kondo, group, call the
   element builders → the engine-agnostic FACTS `{:roots :var-usages}`. The extractor OWNS no
   vocabulary — it EMITS instances by tag (stamping `:extracted true`). It is the HOOK for the
   `fukan.model.extraction` plug-point; the composition root registers `extract-roots` as the fact
   extractor (the native Cozo build assembles the facts + grounds the :calls graph). clj-kondo is
   the wheel we don't reinvent."
  (:require [clj-kondo.core :as kondo]
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

(defn extract-roots
  "The engine-agnostic extraction FACTS over the Clojure source under `paths`:
   `{:roots [[id InstanceValue]…] :var-usages […]}` — the Module/Operation roots
   (Operations stamped with DIRECT effects; Modules `:val/extracted true`) plus the
   clj-kondo var-usages used to ground the `:calls` graph. Both build paths (the
   datascript `extract` and the native cozo build) assemble these same facts."
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
     :var-usages var-usages}))
