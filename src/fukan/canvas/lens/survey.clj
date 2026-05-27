(ns fukan.canvas.lens.survey
  "Survey orchestration — invoke a set of lenses against a canvas db and
   synthesize their outputs into one unified report.

   Lenses are looked up by id via `fukan.canvas.lens.registry/lens-by-id`.
   Unknown ids produce a warning entry in `:survey/results` rather than
   throwing; partial surveys are useful when an experimental lens id is
   wrong but the rest of the set still has signal to contribute.

   See doc/plans/2026-05-26-canvas-substrate-phase-5.md
   § The lens substrate (Phase 5's pluggability core)."
  (:require [clojure.string :as str]
            [fukan.canvas.lens.registry :as registry]))

(defn- run-lens
  "Invoke one lens. If the lens provides `:compute`, run it first; pass the
   findings (or nil) to `:render`. Return the per-lens result map."
  [{:keys [id description compute render]} canvas-db opts]
  (let [findings (when compute (compute canvas-db opts))
        rendered (render findings opts)]
    {:lens/id          id
     :lens/description description
     :lens/findings    findings
     :lens/rendered    rendered}))

(defn- warning-entry
  [lens-id]
  {:lens/id      lens-id
   :lens/warning (str "no lens registered with id " (pr-str lens-id))})

(defn- assemble-rendered
  "Concatenate the per-lens markdown sections into one survey string.
   Warnings render as italic notes; valid lenses render with an H2 header
   derived from their description."
  [results]
  (->> results
       (map (fn [r]
              (if-let [w (:lens/warning r)]
                (str "_warning: " w "_\n")
                (str "## " (:lens/description r) "\n\n" (:lens/rendered r) "\n"))))
       (str/join "\n")))

(defn run
  "Run the named lenses against `canvas-db`. `lens-ids` is a seq of lens
   identifiers (keywords). `opts` is a map passed through to every lens's
   :compute and :render.

   Returns:
     {:survey/lenses   [<id>]
      :survey/results  [<per-lens-result-or-warning>]
      :survey/rendered <concatenated markdown>}

   Unknown lens ids produce warning entries — never throws."
  ([canvas-db lens-ids]      (run canvas-db lens-ids {}))
  ([canvas-db lens-ids opts]
   (let [ids     (vec lens-ids)
         results (mapv (fn [id]
                         (if-let [lens (registry/lens-by-id id)]
                           (run-lens lens canvas-db opts)
                           (warning-entry id)))
                       ids)]
     {:survey/lenses   ids
      :survey/results  results
      :survey/rendered (assemble-rendered results)})))
