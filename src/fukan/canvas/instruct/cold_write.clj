(ns fukan.canvas.instruct.cold-write
  "cold-write scenario — wrap a canvas module's Layer-A projections with
   the situational framing for writing an implementation file from
   scratch.

   Input is a `:module-id` plus the list of Layer-A `:projections` covering
   the module's entities (the caller — typically the canvas-author LLM —
   computes those by walking the module's canvas declarations through the
   project lens). Output is one instruction the implementing LLM consumes
   end-to-end.

   The load-bearing context contributions:

   * **Project conventions** — small inline list of the most relevant
     rules + a pointer to the full docs (CLAUDE.md, canvas-authoring-
     system-prompt). Capable code-synthesis LLMs handle exemplars better
     than rule lists, so this stays terse on purpose.

   * **Matching-pattern neighbors** — file-path citations to sibling
     `src/fukan/*` modules with similar Model-element-kind mix. The
     implementing LLM reads them on demand; this scenario only cites.

   * **The Layer-A specs** — every entity (or the `:include-entity-ids`
     subset) rendered as its own markdown subsection.

   See doc/plans/2026-05-27-scenario-handoff-design.md
   § cold-write scenario."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [fukan.canvas.instruct.render :as render]))

;; ---------------------------------------------------------------------------
;; Conventions — load-bearing inline list. The implementing LLM has Read
;; access to the full docs on disk; the pointer keeps the prompt sized
;; for one-pass review.

(def ^:private default-load-bearing-conventions
  ["Records project as Malli `[:map ...]` schemas; module-scoped types stay PascalCase."
   "Function-shaped affordances use kebab-case; types stay PascalCase."
   "Invariants project as `(defn name [model] ...)` predicate fns whose body is a `(throw ...)` stub carrying canvas-id metadata."
   "Field-level optionality renders as `{:optional true}` in the `:map`."
   "Cross-module type refs (e.g. `:cluster/NodeId` from a sibling module) render as qualified keywords; the Malli registry resolves them."
   "ns docstring should reference the canvas module path so future drift readers see the design source."])

(def ^:private default-conventions-pointer
  {:canvas-authoring-prompt "doc/canvas-authoring-system-prompt.md"
   :project-claude-md       "CLAUDE.md"
   :load-bearing-conventions default-load-bearing-conventions})

;; ---------------------------------------------------------------------------
;; Address helpers

(defn- module-coord->canvas-path
  "Convention: dot→slash. `distributed.cluster` →
   `canvas/distributed/cluster.clj`."
  [module-id]
  (when (string? module-id)
    (str "canvas/"
         (-> module-id
             (str/replace #"-" "_")
             (str/replace #"\." "/"))
         ".clj")))

(defn- module-coord->src-path
  "Convention: dot→slash + dash→underscore. `distributed.cluster` →
   `src/fukan/distributed/cluster.clj`. Mirrors Layer-A's `ns->path`
   helper for the project's own modules — every entity in the module
   targets the same src/ file by convention.

   Falls back to the first projection's `:target.path` if the convention
   doesn't apply (external lenses with different naming)."
  [module-id projections]
  (or (when (string? module-id)
        (str "src/fukan/"
             (-> module-id
                 (str/replace #"-" "_")
                 (str/replace #"\." "/"))
             ".clj"))
      (some-> projections first :target :path)))

;; ---------------------------------------------------------------------------
;; Subsetting + neighbor selection

(defn- subset-projections
  "Filter `projections` by `:include-entity-ids` when present. nil/empty
   filter ⇒ all projections; preserves input order."
  [projections include-ids]
  (if (seq include-ids)
    (let [include? (set include-ids)]
      (filterv #(include? (:model-element-id %)) projections))
    (vec projections)))

(defn- kind-mix
  "Count projections by `:projection-kind`. Used by the neighbor heuristic
   as a similarity signal."
  [projections]
  (frequencies (map :projection-kind projections)))

(defn- default-neighbor-candidates
  "Fallback neighbor list when caller doesn't supply one. Hardcoded
   examples for the fukan-on-fukan trial target. External callers can
   override via `:neighbors` opt; this list is the convention anchor
   for the trial-run scenarios surfaced in Sprint 4."
  []
  [{:canvas-path "canvas/infra/server.clj"
    :src-path    "src/fukan/infra/server.clj"
    :why         "Records + functions + getter — a similar kind-mix to most distributed.* modules."}
   {:canvas-path "canvas/infra/model.clj"
    :src-path    "src/fukan/infra/model.clj"
    :why         "kebab-case fn naming + invariants commented for property-test deferral."}])

(defn- pick-neighbors
  "Apply the neighbor-selection heuristic.

   * If opts supplies an explicit `:neighbors` list, return it verbatim
     (caller knows best).
   * Otherwise, return the default neighbor set, capped at
     `(:neighbor-count opts 3)`.

   Both modes filter neighbors whose `:src-path` does not exist on disk
   — citing a phantom file would erode trust in the rendered output."
  [opts]
  (let [explicit (:neighbors opts)
        cap      (or (:neighbor-count opts) 3)
        all      (or explicit (default-neighbor-candidates))]
    (->> all
         (filter #(or (nil? (:src-path %))
                      (.exists (io/file (:src-path %)))))
         (take cap)
         vec)))

;; ---------------------------------------------------------------------------
;; build-context

(defn- build-context
  "Produce the cold-write scenario-context map. Layer-A specs are passed
   in via `opts :projections`; this fn doesn't query the canvas db
   itself (Layer-B is pure consumption of Layer-A's output)."
  [_code-spec opts]
  (let [{:keys [module-id projections include-entity-ids
                target-file-exists?]} opts
        subset      (subset-projections projections include-entity-ids)
        target-file (module-coord->src-path module-id projections)]
    {:module-id              module-id
     :module-canvas-path     (module-coord->canvas-path module-id)
     :target-file            target-file
     :target-already-exists? (boolean target-file-exists?)
     :projections            subset
     :kind-mix               (kind-mix subset)
     :conventions-pointer    default-conventions-pointer
     :neighbors              (pick-neighbors opts)
     :discipline-prose
     (str "You are writing the implementation of canvas." module-id
          " from scratch. The canvas is the design; your job is the "
          "code-side projection. Follow the project conventions even "
          "where the spec is silent. Draw on the neighbor modules for "
          "style and on the canvas docstrings for semantic intent.")}))

;; ---------------------------------------------------------------------------
;; render — markdown assembly

(defn- frame-section
  [{:keys [module-id projections]}]
  (let [n (count projections)]
    (render/section
     "## What you're doing"
     (str "You are writing the implementation file for **canvas." module-id
          "** from scratch. The canvas declares " n " entities (counted "
          "after any `:include-entity-ids` subsetting). Your output: one "
          "Clojure file under `src/fukan/` that projects each canvas "
          "declaration into the matching code form, respecting project "
          "conventions where the spec is silent."))))

(defn- module-summary-section
  [{:keys [module-id module-canvas-path target-file target-already-exists?
           kind-mix projections]}]
  (render/section
   "## The canvas module"
   (render/bulleted
    (cond-> []
      module-id           (conj (str "**Module:** `" module-id "`"))
      module-canvas-path  (conj (str "**Canvas path:** `" module-canvas-path "`"))
      target-file         (conj (str "**Target file:** `" target-file "`"
                                     (if target-already-exists?
                                       " (already exists — append to it)"
                                       " (does not yet exist)")))))
   (str (count projections) " entities total. Kind-mix:")
   (render/bulleted
    (map (fn [[k n]]
           (str "`" k "` × " n))
         (sort-by first kind-mix)))))

(defn- entity-section
  [{:keys [model-element-id projection-kind target template prose]} idx]
  (render/section
   (str "### " (inc idx) ". `" (:symbol target) "`"
        " — " (subs (str projection-kind) 1))
   (str "Stable-id: `" model-element-id "`")
   (when-not (str/blank? prose) prose)
   (when template (render/fenced "clojure" template))))

(defn- specs-section
  [{:keys [projections]}]
  (apply render/section
         "## The code specs (Layer-A projections, per entity)"
         (map-indexed (fn [i p] (entity-section p i)) projections)))

(defn- conventions-section
  [{:keys [conventions-pointer]}]
  (let [{:keys [canvas-authoring-prompt project-claude-md
                load-bearing-conventions]} conventions-pointer]
    (render/section
     "## Project conventions"
     (str "Full references (Read on demand): `" project-claude-md "`, "
          "`" canvas-authoring-prompt "`.")
     "Load-bearing conventions for this task:"
     (render/bulleted load-bearing-conventions))))

(defn- neighbors-section
  [{:keys [neighbors]}]
  (if (seq neighbors)
    (render/section
     "## Matching-pattern neighbors"
     (str "When the spec gives you a signature but not a body, look at "
          "these for style. Read them on demand; they are not embedded.")
     (render/bulleted
      (map (fn [{:keys [src-path canvas-path why]}]
             (str "`" src-path "`"
                  (when canvas-path (str " (canvas: `" canvas-path "`)"))
                  (when why (str " — " why))))
           neighbors)))
    (render/section
     "## Matching-pattern neighbors"
     "_No neighbor modules cited. Lean on the project conventions._")))

(defn- discipline-section
  [{:keys [discipline-prose target-already-exists?]}]
  (render/section
   "## Discipline"
   discipline-prose
   (render/bulleted
    (cond-> ["Match canvas declaration order — types first, behavioral commitments next, accessors, then functions."
             "Follow the project conventions even where the spec is silent."
             "Leave throw-stub bodies in place where the Layer-A template shipped one — bodies land in subsequent rounds."
             "After writing, run `clj -M:test` to confirm the file loads."]
      target-already-exists?
      (conj "**The target file already exists.** Append to it; preserve its existing ns form, requires, and unrelated content.")))))

(defn- output-section
  [{:keys [target-file]}]
  (render/section
   "## Output format"
   (str "Write the new file (or append to the existing one) at `"
        target-file "` using the `Write` or `Edit` tool. After writing, "
        "report:")
   (render/bulleted
    ["The file path written"
     "The entity count"
     "The result of the test run"])))

(defn- render-fn
  "Produce the full instruction map for cold-write."
  [_code-spec scenario-context _opts]
  (let [body (render/section
              "# Implementation instruction — cold-write"
              (frame-section scenario-context)
              (module-summary-section scenario-context)
              (conventions-section scenario-context)
              (neighbors-section scenario-context)
              (specs-section scenario-context)
              (discipline-section scenario-context)
              (output-section scenario-context))]
    {:scenario-id      :code-side/cold-write
     :code-spec        (or _code-spec {})
     :scenario-context scenario-context
     :rendered         body}))

;; ---------------------------------------------------------------------------
;; Scenario declaration

(def scenario
  "cold-write — write a canvas module's implementation from scratch.
   Caller supplies the Layer-A projections for the module's entities;
   this scenario adds project-convention context + matching-neighbor
   citations + per-entity rendered specs."
  {:scenario-id     :code-side/cold-write
   :description     "Write a canvas module's implementation from scratch."
   :prompt-fragment
   (str "Frame the work as writing a new implementation file. The canvas "
        "module is the design source; convention + matching neighbors "
        "supply the style; the implementing LLM mechanically translates "
        "each entity into its idiomatic code form.")
   :build-context   build-context
   :render          render-fn})
