(ns fukan.vocabulary.allium.pipeline
  "Pipeline orchestrator: source root walk → per-file analyzer → cross-file
   merge → validated Model. Plan 2b's top-level entry point.

   Two passes:
     1. Per-file analysis (sequential). For each `.allium` file under
        `source-root`, parse the AST, derive a module coordinate from the
        file's path, and run `analyzer/analyze-file` with the file's
        use-alias map. Cross-module references inside a file (qualified
        type refs, surface exposes, contract refs, when:/emits event
        names) are resolved at this stage via the alias map; the analyzer
        creates minimal stub primitives at the imported coordinate when
        the real referent is not yet known.
     2. Cross-file stub unification (§3.6). After all files have been
        analyzed, walk the Model for `Allium::ExternalEntity` stubs and
        merge each stub with the real Container that bears the same local
        name when exactly one match exists across the analysed corpus.
        Ambiguous matches (multiple real Containers with the same local
        name) are left unresolved with a warning; absent matches keep the
        stub intact."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [fukan.libs.allium.parser :as parser]
            [fukan.vocabulary.allium.analyzer :as analyzer]
            [fukan.vocabulary.allium.tags :as tags]
            [fukan.model.build :as build])
  (:import (java.nio.file Paths)))

;; ---------------------------------------------------------------------------
;; File discovery + coordinate derivation
;; ---------------------------------------------------------------------------

(defn- find-allium-files
  "Recursively walk `root` and return absolute paths to all .allium files,
   sorted for deterministic load order."
  [root]
  (->> (file-seq (io/file root))
       (filter #(.isFile %))
       (filter #(str/ends-with? (.getName %) ".allium"))
       (map #(.getCanonicalPath %))
       sort))

(defn- coordinate-of
  "Coordinate = relative path from source root, minus the .allium extension."
  [root abs-path]
  (let [root-canonical (.getCanonicalPath (io/file root))
        prefix         (str root-canonical "/")]
    (-> abs-path
        (cond-> (str/starts-with? abs-path prefix)
                (subs (count prefix)))
        (str/replace-first #"\.allium$" ""))))

(defn- canonicalise-use-path
  "Convert a raw `use \"<path>\" as <alias>` path string into a root-relative
   coordinate that matches `coordinate-of`'s output (no `.allium` extension,
   no leading `/`, no `./` or `../` segments).

   `host-coord` is the coordinate of the file containing the `use` declaration
   (e.g. `fukan/web/views/spec`). Relative paths starting with `./` or `../`
   resolve against the host file's *directory* (the parent of `host-coord`).
   Absolute-looking paths (no leading `./` or `../`) are treated as already
   root-relative and only stripped of their `.allium` suffix and leading `/`.

   Examples (root-relative outputs):
     host=`fukan/web/spec`           path=`./views/spec.allium`     → `fukan/web/views/spec`
     host=`fukan/web/spec`           path=`../model/spec.allium`    → `fukan/model/spec`
     host=`fukan/model/pipeline`     path=`./spec.allium`           → `fukan/model/spec`
     host=`b`                        path=`a`                       → `a`
     host=anything                   path=`/some/abs.allium`        → `some/abs`"
  [host-coord raw-path]
  (let [;; Strip trailing .allium first (it survives path arithmetic anyway,
        ;; but stripping before resolution avoids carrying it through normalize)
        stripped (str/replace-first raw-path #"\.allium$" "")
        relative? (or (str/starts-with? stripped "./")
                      (str/starts-with? stripped "../"))
        empty-str-array (into-array String [])]
    (if relative?
      (let [host-path (Paths/get host-coord empty-str-array)
            host-dir  (or (.getParent host-path)
                          (Paths/get "" empty-str-array))
            resolved  (-> host-dir (.resolve stripped) .normalize str)]
        ;; Strip any leading "/" to keep paths aligned with coordinate-of output.
        (str/replace-first resolved #"^/" ""))
      ;; Absolute-looking path: just strip leading "/" if any.
      (str/replace-first stripped #"^/" ""))))

(defn- canonicalise-use-aliases
  "Apply `canonicalise-use-path` to every value in the raw alias map for the
   file at `host-coord`. Returns a new map with the same keys but root-relative,
   `.allium`-stripped coordinates."
  [host-coord raw-aliases]
  (reduce-kv (fn [acc alias raw-path]
               (assoc acc alias (canonicalise-use-path host-coord raw-path)))
             {}
             raw-aliases))

(defn- register-allium-tags [model]
  (reduce build/add-tag-definition model tags/allium-tag-definitions))

;; ---------------------------------------------------------------------------
;; External-entity stub unification (§3.6)
;; ---------------------------------------------------------------------------

(defn- local-name
  "Extract the local-name portion from a `<coord>::<name>` primitive id.
   Returns nil when the id has no `::` separator (e.g. module ids)."
  [primitive-id]
  (when-let [idx (str/last-index-of primitive-id "::")]
    (subs primitive-id (+ idx 2))))

(defn- external-stub?
  "True iff `primitive-id` carries an `Allium::ExternalEntity` tag application
   in `tag-apps`."
  [tag-apps primitive-id]
  (some (fn [ta]
          (and (= "Allium" (-> ta :tag :namespace))
               (= "ExternalEntity" (-> ta :tag :name))
               (= :target/primitive (-> ta :target :case))
               (= primitive-id (-> ta :target :id))))
        tag-apps))

(defn- real-containers-by-local-name
  "Walk all Containers in the Model and group by local-name (skipping
   ExternalEntity stubs themselves). Returns
     local-name → vector of [container-id Container]
   so each candidate carries its full id for resolution."
  [model]
  (let [tag-apps (:tag-apps model)]
    (reduce-kv
      (fn [acc id prim]
        (if (and (= :primitive/container (:kind prim))
                 (not (external-stub? tag-apps id)))
          (if-let [lname (local-name id)]
            (update acc lname (fnil conj []) [id prim])
            acc)
          acc))
      {}
      (:primitives model))))

(defn- rewrite-endpoint
  "If `endpoint` references `from-id` (as primitive-ref or substrate-address),
   return it rewritten to point at `to-id`; otherwise return it unchanged."
  [endpoint from-id to-id]
  (case (:case endpoint)
    :endpoint/primitive
    (if (= from-id (:id endpoint))
      (assoc endpoint :id to-id)
      endpoint)

    :endpoint/substrate
    (if (= from-id (:container endpoint))
      (assoc endpoint :container to-id)
      endpoint)

    endpoint))

(defn- retarget-edges
  "Rewrite both endpoints of every edge that references `from-id` so they
   reference `to-id` instead."
  [edges from-id to-id]
  (mapv (fn [e]
          (-> e
              (update :from rewrite-endpoint from-id to-id)
              (update :to   rewrite-endpoint from-id to-id)))
        edges))

(defn- retarget-type
  "Recursively rewrite Type values that reference `from-id` via a
   Composite-named shape so they reference `to-id` instead. Walks
   collection element types, keyed-collection key types, union members,
   and inline-composite field types."
  [type-val from-id to-id]
  (cond
    (nil? type-val) type-val

    (= :type/composite (:case type-val))
    (let [shape (:shape type-val)]
      (cond
        (and (= :shape/named (:case shape))
             (= from-id (:container shape)))
        (assoc-in type-val [:shape :container] to-id)

        (= :shape/inline (:case shape))
        (update-in type-val [:shape :fields]
                   (fn [fs] (mapv (fn [fs-spec]
                                    (update fs-spec :type retarget-type from-id to-id))
                                  fs)))

        :else type-val))

    (= :type/collection (:case type-val))
    (let [of'        (retarget-type (:of type-val) from-id to-id)
          semantics' (if (and (map? (:semantics type-val))
                              (= :semantics/keyed (:case (:semantics type-val))))
                       (update (:semantics type-val) :key-type retarget-type from-id to-id)
                       (:semantics type-val))]
      (assoc type-val :of of' :semantics semantics'))

    (= :type/union (:case type-val))
    (update type-val :types
            (fn [ts] (mapv #(retarget-type % from-id to-id) ts)))

    :else type-val))

(defn- retarget-field-types [fields from-id to-id]
  (mapv (fn [f] (update f :type-ref retarget-type from-id to-id))
        fields))

(defn- retarget-parameter-types [params from-id to-id]
  (mapv (fn [p] (update p :type-ref retarget-type from-id to-id))
        params))

(defn- retarget-primitive-types
  "Rewrite any Type values stored inline on a primitive's substrate so that
   references to `from-id` point at `to-id`."
  [prim from-id to-id]
  (case (:kind prim)
    :primitive/container
    (cond-> prim
      (:fields prim) (update :fields retarget-field-types from-id to-id))

    :primitive/operation
    (cond-> prim
      (:parameters prim) (update :parameters retarget-parameter-types from-id to-id)
      (:return-type prim) (update :return-type retarget-type from-id to-id))

    :primitive/event
    (cond-> prim
      (:parameters prim) (update :parameters retarget-parameter-types from-id to-id))

    prim))

(defn- retarget-all-primitive-types [primitives from-id to-id]
  (reduce-kv
    (fn [acc id prim]
      (assoc acc id (retarget-primitive-types prim from-id to-id)))
    {}
    primitives))

(defn- retarget-tag-applications
  "Rewrite tag applications that target `from-id` (as a primitive target or
   as the container of a substrate target) so they target `to-id` instead.
   Also drops any `Allium::ExternalEntity` applications that, post-retarget,
   would end up applied to the canonical Container (the stub's external-ness
   was a use-site framing, not a property of the entity)."
  [tag-apps from-id to-id]
  (->> tag-apps
       (map (fn [ta]
              (let [tgt (:target ta)
                    tgt' (case (:case tgt)
                           :target/primitive
                           (if (= from-id (:id tgt))
                             (assoc tgt :id to-id)
                             tgt)
                           :target/substrate
                           (if (= from-id (:container tgt))
                             (assoc tgt :container to-id)
                             tgt)
                           tgt)]
                (assoc ta :target tgt'))))
       (remove (fn [ta]
                 (and (= "Allium" (-> ta :tag :namespace))
                      (= "ExternalEntity" (-> ta :tag :name))
                      (= :target/primitive (-> ta :target :case))
                      (= to-id (-> ta :target :id)))))
       vec))

(defn- merge-stub-into-canonical
  "Drop the stub primitive at `stub-id` and rewrite all edges, tag
   applications, and inline Type references on remaining primitives so
   that they point at `canonical-id`. The canonical Container's substrate
   is preserved verbatim; the `Allium::ExternalEntity` tag is removed
   from the canonical (since the stub's external-ness was a use-site
   framing, not a property of the entity itself)."
  [model stub-id canonical-id]
  (-> model
      (update :primitives dissoc stub-id)
      (update :primitives retarget-all-primitive-types stub-id canonical-id)
      (update :edges retarget-edges stub-id canonical-id)
      (update :tag-apps retarget-tag-applications stub-id canonical-id)))

(defn- warn!
  "Emit a pipeline warning to stderr. Plan 4 may replace this with the
   methodology validator; for now a side-effecting message is enough to
   surface unresolved/ambiguous stubs during analysis."
  [msg ctx]
  (binding [*out* *err*]
    (println (str "[allium-pipeline] " msg " " (pr-str ctx)))))

(defn- unify-external-entity-stubs
  "Second pass: for each Container primitive that carries the
   `Allium::ExternalEntity` tag, locate real Containers across the Model
   whose local-name matches. If exactly one real Container matches, merge
   the stub into it (drop stub, retarget edges + tag-apps, strip the
   ExternalEntity tag from the canonical). If zero matches, leave the stub
   in place. If two or more matches, leave the stub in place and warn —
   the analysed model is ambiguous and the methodology must disambiguate."
  [model]
  (let [stub-ids (->> (:tag-apps model)
                      (filter (fn [ta]
                                (and (= "Allium" (-> ta :tag :namespace))
                                     (= "ExternalEntity" (-> ta :tag :name))
                                     (= :target/primitive (-> ta :target :case)))))
                      (map (comp :id :target))
                      distinct
                      ;; Stable order — easier to test, easier to debug.
                      sort)]
    (reduce
      (fn [m stub-id]
        ;; Recompute the candidate map at each iteration so earlier merges
        ;; don't shadow later ones (the dropped stub's name no longer
        ;; counts; a newly-canonical name absorbs further stubs cleanly).
        (let [stub-prim (build/get-primitive m stub-id)]
          (if (nil? stub-prim)
            m
            (let [lname  (local-name stub-id)
                  by-ln  (real-containers-by-local-name m)
                  cands  (->> (get by-ln lname)
                              ;; A stub at its own coordinate is not a
                              ;; candidate match for itself; defensive
                              ;; filter (real-containers-by-local-name
                              ;; already skips external stubs but keep
                              ;; this explicit).
                              (remove (fn [[id _]] (= id stub-id))))]
              (cond
                (empty? cands)
                m

                (= 1 (count cands))
                (let [[canonical-id _] (first cands)]
                  (merge-stub-into-canonical m stub-id canonical-id))

                :else
                (do
                  (warn! "ambiguous external-entity stub — leaving unresolved"
                         {:stub stub-id
                          :candidates (mapv first cands)})
                  m))))))
      model
      stub-ids)))

(defn- resolve-cross-module-refs
  "Second pass over the merged Model. Per-file alias resolution happens
   inline in the analyzer (which has the per-file alias map at hand); the
   pipeline's remaining cross-module work is stub-unification per §3.6."
  [model]
  (unify-external-entity-stubs model))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn load-source
  "Walk `source-root`, parse every `.allium` file, run the per-file analyzer
   with each file's use-alias map, then perform cross-file stub
   unification (§3.6). Returns a validated Model.

   Per-file use-alias maps are canonicalised before reaching the analyzer:
   raw paths like `./views/spec.allium` become root-relative coordinates
   (`fukan/web/views/spec`) that line up with `coordinate-of`'s output."
  [source-root]
  (let [allium-files (find-allium-files source-root)
        initial      (register-allium-tags (build/empty-model))]
    (loop [model initial
           files allium-files]
      (if (empty? files)
        (resolve-cross-module-refs model)
        (let [f           (first files)
              coord       (coordinate-of source-root f)
              ast         (parser/parse-file f)
              raw-aliases (analyzer/extract-use-aliases ast)
              use-aliases (canonicalise-use-aliases coord raw-aliases)]
          (recur (analyzer/analyze-file model ast coord use-aliases)
                 (rest files)))))))
