(ns fukan.target.clojure
  "A Clojure code-structure extractor — fukan parsing its OWN `src/`.

   This is the PL-specific half of the extraction seam: it is the only place
   that knows Clojure. It reads clj-kondo's `:analysis` output and maps Clojure
   constructs onto fukan's architecture-level code vocabulary:

     ns               → Module      (a cohesion boundary — the substrate's own,
                                      emitted by `within-module*`)
     defn / defn-     → Operation   (a named unit of computation)

   What it extracts INTO is architecture-characteristic and PL-blind — `Operation`
   names no Clojure construct, and `Module` is the substrate's generic cohesion
   boundary. Swap a different language's extractor in (Python `def`/module, …) and
   the same vocab plus the same cross-layer correspondence law still hold; the
   Clojure-ness stays confined here. clj-kondo is the wheel we don't reinvent."
  (:require [clj-kondo.core :as kondo]
            [fukan.canvas.core.structure :as s :refer [defstructure]]))

(defstructure Operation
  "A named unit of computation owned by a Module — the architecture-level code
   primitive fukan's functional core is built from. Realized in Clojure as a
   `defn`/`defn-`; the vocab itself names no Clojure construct. `:private`
   distinguishes a module's public surface from its internals."
  ;; Code-domain only — `Operation`'s laws (if any) concern code constraints. The
  ;; model↔code correspondence ("a Stage is realized by an Operation") is an
  ;; orthogonal concern and lives in `fukan.target.correspondence`, not here.
  (slot :private (one :Bool)))

(def ^:private fn-defining
  "clj-kondo `:defined-by` values that denote a computation unit (an Operation).
   `def`, `defmacro`, `defmethod`, … are deliberately excluded — only functions."
  #{'clojure.core/defn 'clojure.core/defn-})

(defn- analyze
  "Run clj-kondo over `paths` and return its `:analysis` — namespace + var
   definitions. Reads source (and writes clj-kondo's cache); deterministic output."
  [paths]
  (:analysis (kondo/run! {:lint (vec paths)
                          :config {:output {:analysis true}}})))

(defn extract
  "Extract a structure-db of Modules (cohesion boundaries) and the Operations they
   define from the Clojure source under `paths` (files or directories). Builds the
   db via the substrate's programmatic emission API; each namespace becomes a
   Module, each `defn`/`defn-` an Operation owned by it (`:module/child`)."
  [& paths]
  (let [{:keys [namespace-definitions var-definitions]} (analyze paths)
        ops-by-ns    (group-by :ns (filter #(fn-defining (:defined-by %)) var-definitions))
        module-names (distinct (concat (map :name namespace-definitions)
                                       (keys ops-by-ns)))]
    (s/with-structures*
      (fn []
        (doseq [mname module-names]
          (s/within-module* (str mname)
            (fn []
              (doseq [v (ops-by-ns mname)]
                (s/instantiate! :Operation (str (:name v))
                                (list (list 'private (boolean (:private v)))))))))))))
