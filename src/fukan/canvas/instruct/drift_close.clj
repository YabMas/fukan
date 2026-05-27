(ns fukan.canvas.instruct.drift-close
  "drift-close scenario — wrap a Layer-A code spec with the situational
   framing for closing a known canvas↔code drift gap.

   Input is a Layer-A projection (target file + symbol + template + prose)
   plus a drift-finding offender entry; output is a full instruction the
   implementing LLM consumes.

   The load-bearing context contribution: **neighbor summary**. Code-
   synthesis LLMs imitate the file they're editing; this scenario reads
   the target file, extracts existing top-level defs (names + first-line
   docstrings — no bodies), and folds them into the prompt so the
   implementing LLM matches the surrounding style rather than inventing.

   See doc/plans/2026-05-27-scenario-handoff-design.md
   § drift-close scenario."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [fukan.canvas.instruct.render :as render]))

;; ---------------------------------------------------------------------------
;; Helpers — neighbor-summary extraction
;;
;; Read-on-disk parsing kept deliberately string-shaped. A full reader-
;; based walk would couple drift-close to the host's reader options; the
;; rendered-output use case only needs def-name + first-line docstring,
;; which a small regex extracts safely across the source styles that
;; appear in `src/fukan/**`. Bodies are intentionally not parsed.

(def ^:private sibling-cap
  "Maximum number of sibling defs cited in the rendered output. Cap keeps
   the instruction sized for one-glance LLM consumption — the sample-set
   discipline (style + naming) doesn't gain from longer lists."
  10)

(defn- default-target-file-reader
  "Read `path` (a string) from disk, returning its contents or nil if the
   file does not exist. Pure default — tests inject in-memory readers via
   `opts`."
  [path]
  (when (and path (.exists (io/file path)))
    (slurp path)))

(defn- extract-ns-symbol
  "Pull the namespace symbol from the source string's `(ns …)` form. Best-
   effort regex — Layer-B doesn't need to handle every edge case the
   reader would, just produce a useful hint for the rendered output."
  [source]
  (when source
    (when-let [m (re-find #"\(ns\s+([\w\.\-]+)" source)]
      (second m))))

(defn- extract-ns-docstring
  "Pull the docstring out of the ns form when present. The match is loose
   — we accept anything between the ns symbol and the closing parenthesis
   of the ns form, then look for the first double-quoted string."
  [source]
  (when source
    (when-let [m (re-find #"\(ns\s+[\w\.\-]+\s+\"([^\"]+)\"" source)]
      (second m))))

(defn- extract-requires
  "Pull `:require` clauses from the ns form. Returns a vector of the
   `[ns-symbol & opts]` shapes verbatim, as strings (since this lands in
   prose, not in structured re-use)."
  [source]
  (when source
    (->> (re-seq #"\[[\w\.\-]+(?:\s+:as\s+[\w\.\-]+)?[^\]]*\]" source)
         (filter #(or (str/starts-with? % "[fukan")
                      (str/starts-with? % "[clojure")
                      (str/starts-with? % "[datascript")))
         (vec))))

(defn- first-line
  "Return the first line of a multi-line docstring. Trimmed."
  [docstring]
  (when (string? docstring)
    (-> docstring
        (str/split #"\n" 2)
        first
        str/trim)))

(defn- extract-sibling-defs
  "Walk the source string and return a vector of `{:symbol :first-line}`
   maps for each top-level `def` / `defn` / `defn-` / `defrecord` /
   `defmulti` / `defmethod`. Order matches file order. Capped at
   `sibling-cap`."
  [source]
  (if (str/blank? source)
    []
    (let [pattern #"(?m)^\((def|defn|defn-|defrecord|defmulti|defmethod)\s+(\^?\w+/?\w*\s+)?([\w\-\?\!\*]+)(?:\s+\"([^\"]*)\")?"
          hits    (re-seq pattern source)]
      (->> hits
           (map (fn [[_ _kind _meta sym docstring]]
                  {:symbol     sym
                   :first-line (or (first-line docstring) "")}))
           (take sibling-cap)
           vec))))

(defn- build-what-exists
  "Bundle the extracted ns metadata + sibling-def list into the
   :what-exists-in-target-file map cited in the design doc."
  [source]
  {:ns-symbol        (extract-ns-symbol source)
   :ns-docstring     (extract-ns-docstring source)
   :requires         (extract-requires source)
   :sibling-defs     (extract-sibling-defs source)
   :insertion-point  "end-of-file"})

;; ---------------------------------------------------------------------------
;; build-context

(defn- build-context
  "Read the target file (via opts' reader or disk) and produce the
   scenario-context map. Recognizes the absent-file case (cold-write
   candidate, not drift-close) by setting `:target-file-state :absent`."
  [code-spec opts]
  (let [path     (-> code-spec :target :path)
        reader   (or (:target-file-reader opts) default-target-file-reader)
        source   (reader path)
        finding  (:drift-finding opts)
        present? (string? source)]
    (cond-> {:target-file-state (if present? :present :absent)
             :drift-finding     finding
             :discipline-prose
             (str "You are closing a known canvas↔code drift gap. Do not "
                  "disturb unrelated content. Preserve existing imports "
                  "and the ns form. Add the new definition at the end of "
                  "the file unless a sibling-def insertion point reads "
                  "more naturally.")}
      present? (assoc :what-exists-in-target-file (build-what-exists source)))))

;; ---------------------------------------------------------------------------
;; render — markdown assembly

(defn- frame-section
  []
  (render/section
   "## What you're doing"
   (str "You are closing a known **canvas↔code drift gap** in fukan. "
        "The canvas declared a code-side artifact that does not exist "
        "in `src/`. Your job: add the missing definition to the target "
        "file. Do not disturb unrelated content.")))

(defn- gap-section
  [code-spec finding]
  (let [{:keys [stable-id expected-code-path expected-symbol canvas-kind]} finding
        {:keys [model-element-id]} code-spec]
    (render/section
     "## The gap (canvas -> code)"
     (render/bulleted
      (cond-> []
        true (conj (str "**Canvas declaration:** stable-id `"
                        (or stable-id model-element-id) "`"))
        canvas-kind        (conj (str "**Canvas-kind:** " (name canvas-kind)))
        expected-code-path (conj (str "**Target file:** `" expected-code-path "`"))
        expected-symbol    (conj (str "**Expected symbol:** `" expected-symbol "`"))
        true (conj "**Severity:** :warning (drift is fact-of-discrepancy; resolution is judgment)"))))))

(defn- code-spec-section
  [{:keys [template prose context]}]
  (render/section
   "## The code spec (Layer-A projection)"
   (when-not (str/blank? prose) prose)
   (when template (render/fenced "clojure" template))
   (when-let [src-ref (:canvas-source-ref context)]
     (str "_Canvas source:_ `" src-ref "`"))))

(defn- neighbors-section
  [{:keys [target-file-state what-exists-in-target-file]} target-path]
  (if (= :absent target-file-state)
    (render/section
     "## Neighbor context"
     (str "The target file `" target-path "` does not yet exist on disk. "
          "Create it with an appropriate `(ns ...)` form. (Note: a fully "
          "absent target is the `cold-write` scenario's situation; the "
          "canvas-author may want to switch scenarios.)"))
    (let [{:keys [ns-symbol requires sibling-defs insertion-point]}
          what-exists-in-target-file]
      (render/section
       "## Neighbor context (what's already in the file)"
       (when ns-symbol (str "**ns:** `" ns-symbol "`"))
       (when (seq requires)
         (str "**Requires (selected):**\n\n"
              (render/bulleted (map #(str "`" % "`") requires))))
       (when (seq sibling-defs)
         (str "**Existing top-level defs (sample):**\n\n"
              (render/bulleted
               (map (fn [{:keys [symbol first-line]}]
                      (if (str/blank? first-line)
                        (str "`" symbol "`")
                        (str "`" symbol "` — " first-line)))
                    sibling-defs))))
       (when insertion-point
         (str "**Insertion point:** " insertion-point "."))
       (str "**Match the existing sibling style.** The implementing LLM "
            "should imitate the surrounding file's conventions (naming, "
            "metadata placement, doc-string style) rather than inventing "
            "new ones.")))))

(defn- discipline-section
  [{:keys [discipline-prose]}]
  (render/section
   "## Discipline"
   discipline-prose
   (render/bulleted
    ["Do **not** disturb unrelated content."
     "Preserve the file's existing imports and ns form exactly."
     "After writing, run `clj -M:test` to confirm the file still loads."])))

(defn- output-section
  [{:keys [target]}]
  (render/section
   "## Output format"
   (str "Write the edit directly to `" (:path target) "` using the `Edit` "
        "or `Write` tool. After writing, report:")
   (render/bulleted
    ["The symbol you added"
     "The exact insertion point used"
     "The result of the test run"])))

(defn- render-fn
  "Produce the full instruction map. The structured fields round-trip;
   `:rendered` is the markdown the implementing LLM consumes."
  [code-spec scenario-context _opts]
  (let [finding (:drift-finding scenario-context)
        target  (:target code-spec)
        body    (render/section
                 "# Implementation instruction — drift-close"
                 (frame-section)
                 (gap-section code-spec finding)
                 (code-spec-section code-spec)
                 (neighbors-section scenario-context (:path target))
                 (discipline-section scenario-context)
                 (output-section code-spec))]
    {:scenario-id      :code-side/drift-close
     :code-spec        code-spec
     :scenario-context scenario-context
     :rendered         body}))

;; ---------------------------------------------------------------------------
;; Scenario declaration

(def scenario
  "drift-close — closing a known canvas↔code drift gap. The implementing
   LLM receives the Layer-A code spec plus the target file's existing
   sibling-def summary, and adds the missing definition in place."
  {:scenario-id     :code-side/drift-close
   :description     "Close a known canvas↔code drift gap."
   :prompt-fragment
   (str "Frame the work as closing a gap that drift surfaced. The canvas "
        "is the design; the target file is the place to add the missing "
        "definition; the file's existing siblings are the style anchor.")
   :build-context   build-context
   :render          render-fn})
