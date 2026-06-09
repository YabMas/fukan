(ns fukan.target.clojure
  "A Clojure code-structure extractor — fukan parsing its OWN `src/`.

   This is the PL-specific half of the extraction seam: it is the only place
   that knows Clojure. It reads clj-kondo's `:analysis` output and maps Clojure
   constructs onto fukan's architecture-level code vocabulary:

     ns               → Subsystem   (a cohesion boundary)
     defn / defn-     → Operation   (a named unit of computation, `:extracted true`)

   The extractor OWNS no vocabulary — `Operation`/`Subsystem` are defined in
   `lib.code`; this plug-point only EMITS instances of them by tag
   (stamping `:extracted true` so an extracted Operation is distinguished from an
   authored one), and they meet that grammar at merge. Swap a different language's
   extractor in (Python `def`/module, …) and the same vocab plus the same cross-layer
   correspondence law still hold; the Clojure-ness stays confined here. clj-kondo is
   the wheel we don't reinvent."
  (:require [clj-kondo.core :as kondo]
            [fukan.canvas.core.assemble :as assemble]
            [fukan.canvas.core.structure :as s]))

(def ^:private fn-defining
  "clj-kondo `:defined-by` values that denote a computation unit (an Operation).
   `def`, `defmacro`, `defmethod`, … are deliberately excluded — only functions."
  #{'clojure.core/defn 'clojure.core/defn-})

(defn- analyze
  "Run clj-kondo over `paths` and return its `:analysis` — namespace + var
   definitions. Reads source (and writes clj-kondo's cache); deterministic output."
  [paths]
  (:analysis (kondo/run! {:lint (vec paths)
                          :config {:output {:analysis {:var-definitions {:meta true}}}}})))

(defn extract
  "Extract a structure-db of Subsystems (cohesion boundaries) and the Operations they
   define from the Clojure source under `paths` (files or directories). Builds the
   db programmatically: each namespace becomes a `Subsystem` whose `:child` Operations
   are the `defn`/`defn-`s it defines. Operations are inline-value children of their
   Subsystem — anonymous, owner-path-identified, stamped `:extracted true`, matched to
   authored Operations by NAME via the correspondence law."
  [& paths]
  (let [{:keys [namespace-definitions var-definitions]} (analyze paths)
        ops-by-ns    (group-by :ns (filter #(fn-defining (:defined-by %)) var-definitions))
        module-names (distinct (concat (map :name namespace-definitions)
                                       (keys ops-by-ns)))]
    (assemble/assemble-instances
     (for [mname module-names
           :let [ops (for [v (ops-by-ns mname)]
                       (s/->InstanceValue :Operation (str (:name v)) nil
                                          (cond-> {:val/private (boolean (:private v))
                                                   :val/extracted true}
                                            (:malli/schema (:meta v))
                                            (assoc :val/sig (pr-str (:malli/schema (:meta v)))))
                                          [] false))]]
       [(str mname)
        (s/->InstanceValue :Subsystem (str mname) nil {}
                           [{:rk :child :card :many :targets (vec ops)}] false)]))))
