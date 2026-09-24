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
   name). An `(eq …)` sort interns no constructor, so it is excluded."
  [form]
  (when (and (seq? form)
             (symbol? (first form))
             (= "defstructure" (name (first form))))
    (let [name-sym (second form)
          body     (drop 2 form)
          realized? (some (fn [f]
                            (and (seq? f) (symbol? (first f))
                                 (= "eq" (name (first f)))))
                          body)]
      (when (and (symbol? name-sym) (not realized?))
        (symbol (name name-sym))))))

;; ── source → fully-qualified constructor names ───────────────────────────────

(defn read-forms
  "All top-level forms in `src` (a Clojure source string). Reads with `*read-eval*`
   off and unknown data-readers tolerated; reading stops at end-of-input. defstructure
   forms are plain data, so they read cleanly; any unusual trailing form simply ends
   the scan (the staleness test guards against a real miss).

   ⚠ AUTO-RESOLVED KEYWORDS ARE NEUTRALISED FIRST. `::alias/Sort` needs the reading
   namespace to carry that alias, which this scanner has no reason to build — and the
   reader does not skip what it cannot read, it THROWS, which ends the scan at that
   form. A vocabulary that spells a cross-namespace sort the way the authoring surface
   asks (`::module/Module` in a defrelation body) therefore hid every defstructure
   BELOW it, silently dropping those structures from the generated config. Stripping
   the extra colon leaves an ordinary keyword, which is all the scan needs: it reads
   structure names and `(eq …)` heads, never keywords."
  [src]
  (let [src (str/replace src #"::" ":")
        rdr (java.io.PushbackReader. (java.io.StringReader. src))
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

;; ── the generated config files ───────────────────────────────────────────────

(def default-dirs
  "The source roots scanned for fukan's own defstructures: the kernel act-grammar in
   `src/`, the common grammar in `common/`, the self-model in `canvas/`, and test fixtures."
  ["src" "common" "canvas" "test"])

(def generated-config-path
  "The generated config, merged into the hand-written one via `:config-paths`."
  ".clj-kondo/generated/config.edn")

(def export-dirs
  "The SHIPPED source roots — fukan's base `:paths`. A consumer can only author instances of
   structures defined here; the self-model and test fixtures are not on its classpath."
  ["src" "common"])

(def export-config-path
  "The config fukan ships to consumers. `clj-kondo --copy-configs --dependencies` copies
   `clj-kondo.exports/fukan/fukan/` (with the hook source beside it) out of the jar or git dep
   into the consumer's `.clj-kondo`. fukan's own config merges it too, via `:config-paths`."
  "resources/clj-kondo.exports/fukan/fukan/config.edn")

(def defining-hooks
  "The fixed hooks for the macros that DEFINE structures, relations and correspondences.
   Not derived: there are exactly these three, and they are what a consumer's own
   `defstructure` forms need."
  (sorted-map 'fukan.canvas.core.structure/correspond   'hooks.fukan.structure/correspond
              'fukan.canvas.core.structure/defrelation  'hooks.fukan.structure/defrelation
              'fukan.canvas.core.structure/defstructure 'hooks.fukan.structure/defstructure))

(defn generated-config
  "The clj-kondo config that registers every defstructure instance-constructor under
   `dirs` against the generic hook: `{:hooks {:analyze-call {ns/Name … }}}`."
  [dirs]
  {:hooks {:analyze-call (analyze-call-map (scan dirs))}})

(defn export-config
  "The shipped config: the defining-macro hooks plus every instance-constructor under
   `export-dirs`. It is the whole of what a consumer needs to lint authored instances."
  []
  {:hooks {:analyze-call (into defining-hooks (analyze-call-map (scan export-dirs)))}})

(defn- render
  "`cfg` as config text, one `:analyze-call` entry per line, sorted, so diffs are minimal
   and stable across regenerations. `source` names what the entries were derived from."
  [cfg source]
  (let [m    (into (sorted-map) (get-in cfg [:hooks :analyze-call]))
        body (str/join "\n" (map (fn [[k v]] (str "   " k " " v)) m))]
    (str ";; GENERATED by `clojure -M:kondo` — do not edit by hand.\n"
         ";; Per-structure clj-kondo hooks for the defstructure DSL, derived from\n"
         ";; " source ".\n"
         "{:hooks\n {:analyze-call\n  {" (str/triml (str "\n" body)) "}}}\n")))

(defn- write! [path text n]
  (io/make-parents path)
  (spit path text)
  (println "wrote" n "hook entries to" path))

(defn write-config!
  "Regenerate `generated-config-path` from `dirs` (default `default-dirs`), and the shipped
   `export-config-path`."
  ([] (write-config! default-dirs))
  ([dirs]
   (let [cfg (generated-config dirs)
         exp (export-config)]
     (write! generated-config-path
             (render cfg (str "the defstructure forms in: " (str/join " " dirs)))
             (count (get-in cfg [:hooks :analyze-call])))
     (write! export-config-path
             (render exp (str "the defining macros, and the defstructure forms in: "
                              (str/join " " export-dirs)))
             (count (get-in exp [:hooks :analyze-call]))))))

(defn -main
  "CLI entry: regenerate the clj-kondo hook config from a source scan."
  [& _]
  (write-config!)
  (shutdown-agents))
