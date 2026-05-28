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

(defn- locate-existing-def
  "Find the line range of a top-level `(def SYM …)` or `(defn SYM …)` form
   in `source` so shape-drift framings can direct the implementing LLM to
   rewrite in place. Returns `{:start-line N :end-line N :preview \"…\"}`
   or nil when the symbol isn't found.

   Line range is computed by walking forward from the head paren and
   tracking paren depth; preview carries the first three lines of the
   form so the implementing LLM can confirm it's editing the right thing.
   Approximate by design — drift-close only needs a useful hint, not a
   reader-faithful AST."
  [source target-symbol]
  (when (and (string? source) (string? target-symbol) (seq target-symbol))
    (let [;; Use Pattern.quote-equivalent for the symbol's regex form:
          ;; escape any regex metacharacters with a backslash, since the
          ;; symbol is user-supplied (could carry ? ! - etc).
          esc        (str/escape target-symbol
                                 {\? "\\?" \! "\\!" \. "\\." \* "\\*"
                                  \+ "\\+" \( "\\(" \) "\\)" \[ "\\["
                                  \] "\\]" \{ "\\{" \} "\\}" \^ "\\^"
                                  \$ "\\$"})
          pattern    (re-pattern
                      (str "(?m)^\\((def|defn|defn-|defrecord|defmulti)\\s+"
                           "(?:\\^[\\w/:\\{\\}]+\\s+)?"
                           esc "(\\s|\\b|\\n)"))
          match      (re-find pattern source)]
      (when match
        (let [head-idx  (str/index-of source (first match))
              ;; Find the line number (1-based) of head-idx.
              start-line (inc (count (filter #(= % \newline)
                                             (subs source 0 head-idx))))
              ;; Walk forward, balancing parens, ignoring string contents.
              len        (count source)
              end-idx    (loop [i     head-idx
                                depth 0
                                in-string? false
                                escape-next? false]
                           (if (>= i len)
                             (dec len)
                             (let [ch (.charAt ^String source i)]
                               (cond
                                 escape-next?
                                 (recur (inc i) depth in-string? false)

                                 (and in-string? (= ch \\))
                                 (recur (inc i) depth in-string? true)

                                 (= ch \")
                                 (recur (inc i) depth (not in-string?) false)

                                 in-string?
                                 (recur (inc i) depth in-string? false)

                                 (= ch \()
                                 (recur (inc i) (inc depth) in-string? false)

                                 (= ch \))
                                 (if (= depth 1)
                                   i
                                   (recur (inc i) (dec depth) in-string? false))

                                 :else
                                 (recur (inc i) depth in-string? false)))))
              end-line   (inc (count (filter #(= % \newline)
                                             (subs source 0 end-idx))))
              preview    (->> (subs source head-idx (min (count source) (+ head-idx 240)))
                              str/split-lines
                              (take 3)
                              (str/join "\n"))]
          {:start-line start-line
           :end-line   end-line
           :preview    preview})))))

;; ---------------------------------------------------------------------------
;; build-context

(defn- shape-drift?
  "True when the drift-finding's :check kind requires rewrite-in-place
   framing (today only :inspect.drift/shape-drift-on-record)."
  [finding]
  (= :inspect.drift/shape-drift-on-record (:check finding)))

(defn- build-context
  "Read the target file (via opts' reader or disk) and produce the
   scenario-context map. Recognizes the absent-file case (cold-write
   candidate, not drift-close) by setting `:target-file-state :absent`.

   For shape-drift findings (rewrite-in-place), additionally locates the
   existing def's line range so the rendered output can cite it. The
   extraction is only attempted when both the file is present and the
   finding is shape-drift — every other kind keeps the cheap path."
  [code-spec opts]
  (let [path     (-> code-spec :target :path)
        reader   (or (:target-file-reader opts) default-target-file-reader)
        source   (reader path)
        finding  (:drift-finding opts)
        present? (string? source)
        sym      (-> code-spec :target :symbol)
        existing (when (and present? (shape-drift? finding) sym)
                   (locate-existing-def source sym))]
    (cond-> {:target-file-state (if present? :present :absent)
             :drift-finding     finding
             :discipline-prose
             (if (shape-drift? finding)
               (str "You are closing a known canvas↔code drift gap. Do not "
                    "disturb unrelated content. Preserve existing imports "
                    "and the ns form. Rewrite the diverging def in place; "
                    "do NOT append a duplicate at the end of the file.")
               (str "You are closing a known canvas↔code drift gap. Do not "
                    "disturb unrelated content. Preserve existing imports "
                    "and the ns form. Add the new definition at the end of "
                    "the file unless a sibling-def insertion point reads "
                    "more naturally."))}
      present? (assoc :what-exists-in-target-file (build-what-exists source))
      existing (assoc :existing-def existing))))

;; ---------------------------------------------------------------------------
;; render — markdown assembly

;; ---------------------------------------------------------------------------
;; Kind-dispatched sections
;;
;; The frame / gap / neighbor-insertion / output prose all depend on what KIND
;; of drift gap we are closing. The dispatch key is the finding's `:check`
;; keyword. The `:default` branch carries a generic-but-correct framing so a
;; future drift kind doesn't crash drift-close or hard-code missing-impl prose.

(defn- drift-kind
  "Pull the dispatch key from the scenario-context. Falls back to :default
   when no finding is carried or no `:check` is present — defensive so a
   caller-supplied bare offender map still renders something sane."
  [scenario-context]
  (or (some-> scenario-context :drift-finding :check)
      :default))

(defmulti ^:private frame-section
  "Kind-specific 'What you're doing' framing prose."
  drift-kind)

(defmethod frame-section :inspect.drift/missing-implementation
  [_]
  (render/section
   "## What you're doing"
   (str "You are closing a known **canvas↔code drift gap** in fukan. "
        "The canvas declared a code-side artifact that does not exist "
        "in `src/`. Your job: add the missing definition to the target "
        "file. Do not disturb unrelated content.")))

(defmethod frame-section :inspect.drift/shape-drift-on-record
  [_]
  (render/section
   "## What you're doing"
   (str "You are closing a known **canvas↔code drift gap** in fukan. "
        "The canvas declared a record shape; the code-side def exists, "
        "but its fields diverge from the canvas. Your job: **rewrite "
        "the existing def in place** so its shape matches the canvas-side "
        "shape. Do **not** append a duplicate def — the def already "
        "exists. Preserve unrelated content.")))

(defmethod frame-section :default
  [_]
  (render/section
   "## What you're doing"
   (str "You are closing a known **canvas↔code drift gap** in fukan. "
        "The canvas and the code-side artifact have diverged. Your job: "
        "reconcile the code-side with the canvas-declared shape. Do not "
        "disturb unrelated content.")))

(defmulti ^:private gap-section
  "Kind-specific 'The gap' summary."
  (fn [_code-spec scenario-context] (drift-kind scenario-context)))

(defmethod gap-section :inspect.drift/missing-implementation
  [code-spec scenario-context]
  (let [finding (:drift-finding scenario-context)
        {:keys [stable-id expected-code-path expected-symbol canvas-kind]} finding
        {:keys [model-element-id]} code-spec]
    (render/section
     "## The gap (canvas -> code)"
     (render/bulleted
      (cond-> []
        true (conj (str "**Canvas declaration:** stable-id `"
                        (or stable-id model-element-id) "`"))
        true (conj "**Drift kind:** missing-implementation")
        canvas-kind        (conj (str "**Canvas-kind:** " (name canvas-kind)))
        expected-code-path (conj (str "**Target file:** `" expected-code-path "`"))
        expected-symbol    (conj (str "**Expected symbol:** `" expected-symbol "`"))
        true (conj "**Severity:** :warning (drift is fact-of-discrepancy; resolution is judgment)"))))))

(defn- format-field-shape
  "Pretty-print one canvas- or code-side field shape for the gap-section
   bullets. Keeps the format compact (single line) — the implementing LLM
   wants a glance-readable diff, not a structured shape dump."
  [shape]
  (cond
    (nil? shape)     "—"
    (keyword? shape) (pr-str shape)
    :else            (pr-str shape)))

(defn- shape-drift-delta-bullets
  "Convert a shape-drift offender's :delta map into bullet strings citing
   which fields are only-in-canvas, only-in-code, or type-mismatched. Each
   sub-shape contributes at most one bullet line per field so the rendered
   output remains glance-readable; longer divergences fold into the same
   line."
  [{:keys [only-in-canvas only-in-code type-mismatch]}]
  (concat
    (for [[fname shape] only-in-canvas]
      (str "**Only in canvas:** `" (pr-str fname) "` :: "
           (format-field-shape shape)
           " — the code-side def is missing this field."))
    (for [[fname shape] only-in-code]
      (str "**Only in code:** `" (pr-str fname) "` :: "
           (format-field-shape shape)
           " — the canvas does not declare this field; remove it or move "
           "the declaration into the canvas."))
    (for [[fname {:keys [canvas code]}] type-mismatch]
      (str "**Type mismatch:** `" (pr-str fname) "` — canvas declares "
           (format-field-shape canvas) ", code has "
           (format-field-shape code) "."))))

(defmethod gap-section :inspect.drift/shape-drift-on-record
  [code-spec scenario-context]
  (let [finding (:drift-finding scenario-context)
        {:keys [stable-id code-side-path canvas-fields code-fields delta]} finding
        {:keys [model-element-id]} code-spec
        {target-symbol :symbol target-path :path} (:target code-spec)
        bullets (vec
                 (cond-> []
                   true (conj (str "**Canvas declaration:** stable-id `"
                                   (or stable-id model-element-id) "`"))
                   true (conj "**Drift kind:** shape-drift-on-record")
                   target-symbol  (conj (str "**Existing symbol:** `" target-symbol
                                             "` (rewrite this def in place — do not duplicate)"))
                   (or code-side-path target-path)
                   (conj (str "**Target file:** `"
                              (or code-side-path target-path) "`"))
                   true (conj "**Severity:** :warning (drift is fact-of-discrepancy; resolution is judgment)")))
        diff-bullets (vec (shape-drift-delta-bullets delta))]
    (render/section
     "## The gap (canvas -> code)"
     (render/bulleted bullets)
     (when (seq diff-bullets)
       (str "**Field-level divergence:**\n\n"
            (render/bulleted diff-bullets)))
     (when (or canvas-fields code-fields)
       (str "**Raw field shapes for reference:**\n\n"
            (render/bulleted
             (cond-> []
               canvas-fields (conj (str "canvas: " (pr-str canvas-fields)))
               code-fields   (conj (str "code:   " (pr-str code-fields))))))))))

(defmethod gap-section :default
  [code-spec scenario-context]
  (let [finding (:drift-finding scenario-context)
        {:keys [stable-id expected-code-path expected-symbol canvas-kind check]} finding
        {:keys [model-element-id]} code-spec]
    (render/section
     "## The gap (canvas -> code)"
     (render/bulleted
      (cond-> []
        true (conj (str "**Canvas declaration:** stable-id `"
                        (or stable-id model-element-id) "`"))
        check              (conj (str "**Drift kind:** " (name check)))
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

(defn- neighbors-base-section
  "Shared assembly of the neighbor context. Kind-specific overlays
   (insertion-point hint, style call-out) are spliced in by the per-kind
   `neighbors-section` defmethod."
  [{:keys [target-file-state what-exists-in-target-file]} target-path
   {:keys [insertion-prose style-prose]}]
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
       (or insertion-prose
           (when insertion-point
             (str "**Insertion point:** " insertion-point ".")))
       (or style-prose
           (str "**Match the existing sibling style.** The implementing LLM "
                "should imitate the surrounding file's conventions (naming, "
                "metadata placement, doc-string style) rather than inventing "
                "new ones."))))))

(defmulti ^:private neighbors-section
  "Kind-specific neighbor section. Most of the body is shared; the
   insertion-point hint is what diverges across drift kinds."
  (fn [scenario-context _target-path] (drift-kind scenario-context)))

(defmethod neighbors-section :inspect.drift/missing-implementation
  [scenario-context target-path]
  (neighbors-base-section scenario-context target-path {}))

(defmethod neighbors-section :inspect.drift/shape-drift-on-record
  [scenario-context target-path]
  (let [existing (:existing-def scenario-context)
        insertion-prose
        (if existing
          (str "**Insertion point:** rewrite-in-place at lines **"
               (:start-line existing) "–" (:end-line existing)
               "** of `" target-path "`. Replace the existing form; do "
               "NOT append a duplicate def. Existing form opens with:\n\n"
               (render/fenced "clojure" (:preview existing)))
          (str "**Insertion point:** rewrite-in-place. The existing def "
               "should already be present in `" target-path "`; locate it "
               "by symbol and replace its body. Do NOT append a duplicate def."))
        style-prose
        (str "**Match the existing sibling style.** Keep adjacent defs "
             "intact; only the diverging fields need to change. Imitate "
             "the surrounding metadata placement and docstring style.")]
    (neighbors-base-section scenario-context target-path
                            {:insertion-prose insertion-prose
                             :style-prose     style-prose})))

(defmethod neighbors-section :default
  [scenario-context target-path]
  (neighbors-base-section scenario-context target-path {}))

(defn- discipline-section
  [{:keys [discipline-prose]}]
  (render/section
   "## Discipline"
   discipline-prose
   (render/bulleted
    ["Do **not** disturb unrelated content."
     "Preserve the file's existing imports and ns form exactly."
     "After writing, run `clj -M:test` to confirm the file still loads."])))

(defmulti ^:private output-section
  "Kind-specific 'Output format' section — what the implementing LLM is
   expected to report back. The output expectations differ between
   add-at-end (report the symbol added + insertion point) and
   rewrite-in-place (report the lines replaced + what changed)."
  (fn [_code-spec scenario-context] (drift-kind scenario-context)))

(defmethod output-section :inspect.drift/missing-implementation
  [{:keys [target]} _scenario-context]
  (render/section
   "## Output format"
   (str "Write the edit directly to `" (:path target) "` using the `Edit` "
        "or `Write` tool. After writing, report:")
   (render/bulleted
    ["The symbol you added"
     "The exact insertion point used"
     "The result of the test run"])))

(defmethod output-section :inspect.drift/shape-drift-on-record
  [{:keys [target]} _scenario-context]
  (render/section
   "## Output format"
   (str "Rewrite the existing def in `" (:path target) "` using the "
        "`Edit` tool — replace the old form in place. After writing, report:")
   (render/bulleted
    ["The symbol you rewrote"
     "The line range you replaced (start–end)"
     "A one-line summary of what changed (which fields added / removed / retyped)"
     "The result of the test run"])))

(defmethod output-section :default
  [{:keys [target]} _scenario-context]
  (render/section
   "## Output format"
   (str "Write the edit directly to `" (:path target) "` using the `Edit` "
        "or `Write` tool. After writing, report:")
   (render/bulleted
    ["The symbol you touched"
     "The exact edit you applied (insertion-at-end or rewrite-in-place)"
     "The result of the test run"])))

(defn- render-fn
  "Produce the full instruction map. The structured fields round-trip;
   `:rendered` is the markdown the implementing LLM consumes.

   Section assembly is kind-dispatched: each `defmulti` above branches on
   the finding's `:check` so prose can differ between
   missing-implementation, shape-drift-on-record, and unknown future
   kinds."
  [code-spec scenario-context _opts]
  (let [target (:target code-spec)
        body   (render/section
                "# Implementation instruction — drift-close"
                (frame-section scenario-context)
                (gap-section code-spec scenario-context)
                (code-spec-section code-spec)
                (neighbors-section scenario-context (:path target))
                (discipline-section scenario-context)
                (output-section code-spec scenario-context))]
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
