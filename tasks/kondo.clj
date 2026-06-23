(ns tasks.kondo
  "Generate the clj-kondo `:analyze-call` hook entries for fukan's defstructure DSL.

   `defstructure` defines a structure AND interns a macro named for it, used to
   author instances. clj-kondo keys hooks by exact fully-qualified symbol and can't
   discover dynamically-generated macros, so every instance-constructor macro needs
   an entry pointing at the one generic hook `hooks.fukan.structure/instance`. That
   set is a pure function of the defstructure forms in the source — so it is derived
   here, not hand-maintained.

   Lives off `src/` (reachable via the `.` classpath root, like `lib/`) so it is
   neither linted nor extracted into fukan's self-model — it's build tooling."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private hook-fn
  "The one generic clj-kondo hook every instance-constructor macro routes to."
  'hooks.fukan.structure/instance)

;; ── form classification: which forms intern a constructor macro ──────────────

(defn structure-name
  "The constructor-macro name a `defstructure` form interns, or nil when the form
   isn't a constructor-bearing defstructure. Recognises both the referred
   `defstructure` and an aliased `s/defstructure` (matched by the head's simple
   name). A `realized-as` concept interns no constructor, so it is excluded."
  [form]
  (when (and (seq? form)
             (symbol? (first form))
             (= "defstructure" (name (first form))))
    (let [name-sym (second form)
          body     (drop 2 form)
          realized? (some (fn [f]
                            (and (seq? f) (symbol? (first f))
                                 (= "realized-as" (name (first f)))))
                          body)]
      (when (and (symbol? name-sym) (not realized?))
        (symbol (name name-sym))))))

;; ── source → fully-qualified constructor names ───────────────────────────────

(defn read-forms
  "All top-level forms in `src` (a Clojure source string). Reads with `*read-eval*`
   off and unknown data-readers tolerated; reading stops at end-of-input. defstructure
   forms are plain data, so they read cleanly; any unusual trailing form simply ends
   the scan (the staleness test guards against a real miss)."
  [src]
  (let [rdr (java.io.PushbackReader. (java.io.StringReader. src))
        eof (Object.)]
    (binding [*read-eval* false
              *default-data-reader-fn* (fn [_tag v] v)]
      (loop [acc []]
        (let [form (try (read {:eof eof :read-cond :allow} rdr)
                        (catch Exception _ eof))]
          (if (identical? form eof)
            acc
            (recur (conj acc form))))))))

(defn- ns-of
  "The namespace symbol declared by the `(ns …)` form among `forms`, or nil."
  [forms]
  (some (fn [f]
          (when (and (seq? f) (= 'ns (first f)) (symbol? (second f)))
            (second f)))
        forms))

(defn qualified-names
  "The fully-qualified constructor-macro symbols (`ns/Name`) a source string defines,
   in source order. Empty when the source declares no namespace."
  [src]
  (let [forms (read-forms src)
        ns-sym (ns-of forms)]
    (if-not ns-sym
      []
      (into [] (keep (fn [f]
                       (when-let [nm (structure-name f)]
                         (symbol (name ns-sym) (name nm))))
                     forms)))))

;; ── scanning a source tree → the analyze-call map ────────────────────────────

(defn- clj-files [dirs]
  (->> dirs
       (mapcat (fn [d] (file-seq (io/file d))))
       (filter #(and (.isFile ^java.io.File %)
                     (str/ends-with? (.getName ^java.io.File %) ".clj")))))

(defn scan
  "Every fully-qualified constructor-macro symbol defined by `defstructure` across
   the `.clj` files under `dirs`, de-duplicated and sorted (deterministic output)."
  [dirs]
  (->> (clj-files dirs)
       (mapcat (comp qualified-names slurp))
       distinct
       (sort-by str)
       vec))

(defn analyze-call-map
  "A `{ns/Name → hooks.fukan.structure/instance}` map for `names`, sorted by symbol
   so the rendered config is stable across regenerations."
  [names]
  (into (sorted-map) (map (fn [s] [s hook-fn])) names))

;; ── the generated config file ────────────────────────────────────────────────

(def default-dirs
  "The source roots scanned for fukan's own defstructures. `src/` now holds the fukan-native
   ACT grammar (the `Lens`/`Projection`/`Mapping` structures in `core.lens`, instantiated by the
   instruments) plus the law-holder structures in `target.correspondence` — so it is scanned too.
   The kernel still ships no DOMAIN vocabulary; it is opinionated only about the native acts."
  ["src" "lib" "canvas" "test"])

(def generated-config-path
  "The generated config, merged into the hand-written one via `:config-paths`."
  ".clj-kondo/generated/config.edn")

(defn generated-config
  "The clj-kondo config that registers every defstructure instance-constructor under
   `dirs` against the generic hook: `{:hooks {:analyze-call {ns/Name … }}}`."
  [dirs]
  {:hooks {:analyze-call (analyze-call-map (scan dirs))}})

(defn write-config!
  "Regenerate `generated-config-path` from `dirs` (default `default-dirs`). The map is
   printed one entry per line, sorted, so diffs are minimal and stable."
  ([] (write-config! default-dirs))
  ([dirs]
   (let [m   (get-in (generated-config dirs) [:hooks :analyze-call])
         body (str/join "\n" (map (fn [[k v]] (str "   " k " " v)) m))
         out  (str ";; GENERATED by `clojure -M:kondo` — do not edit by hand.\n"
                   ";; Per-structure clj-kondo hooks for the defstructure DSL, derived from\n"
                   ";; the defstructure forms in: " (str/join " " dirs) ".\n"
                   "{:hooks\n {:analyze-call\n  {" (str/triml (str "\n" body)) "}}}\n")]
     (io/make-parents generated-config-path)
     (spit generated-config-path out)
     (println "wrote" (count m) "hook entries to" generated-config-path))))

(defn -main
  "CLI entry: regenerate the clj-kondo hook config from a source scan."
  [& _]
  (write-config!)
  (shutdown-agents))
