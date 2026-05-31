(ns fukan.canvas.lens.core
  "Pluggable lens substrate — the lens contract + validation.

   A lens is a way of seeing the canvas. Each lens is a single namespace
   under `src/fukan/canvas/lens/` declaring a single `lens` var that
   satisfies the contract validated here.

   The contract:

     {:id              <keyword>  ; required; simple or namespaced
      :description     <string>   ; required; one-line summary
      :prompt-fragment <string>   ; required; LLM priming prose
      :compute         <fn>       ; OPTIONAL; (fn [canvas-db opts] -> findings)
      :render          <fn>}      ; required; (fn [findings opts] -> string)

   `:compute` is optional because theoretical lenses (Tar Pit, FCIS, ...)
   may have nothing structural to query — their prompt-fragment is the
   primary contribution. When absent, the survey passes nil as the
   first argument to `:render`.

   The lens substrate is summarised in VISION.md (the pluggable lens
   layer); the full design lives in git history:
   doc/plans/2026-05-26-canvas-substrate-phase-5.md § The lens substrate.")

(defn validate-lens
  "Return a vector of issue strings describing why m is not a valid lens.
   An empty vector means m satisfies the lens contract."
  [m]
  (let [issues (transient [])]
    (when-not (map? m)
      (conj! issues (str "lens must be a map; got " (pr-str (type m)))))
    (when (map? m)
      (when-not (keyword? (:id m))
        (conj! issues (str ":id must be a keyword; got " (pr-str (:id m)))))
      (when-not (string? (:description m))
        (conj! issues (str ":description must be a string; got " (pr-str (:description m)))))
      (when-not (string? (:prompt-fragment m))
        (conj! issues (str ":prompt-fragment must be a string; got " (pr-str (:prompt-fragment m)))))
      (when-not (fn? (:render m))
        (conj! issues (str ":render must be a fn; got " (pr-str (:render m)))))
      (when (and (contains? m :compute) (not (fn? (:compute m))))
        (conj! issues (str ":compute, when present, must be a fn; got " (pr-str (:compute m))))))
    (persistent! issues)))

(defn valid-lens?
  "True iff m satisfies the lens contract."
  [m]
  (empty? (validate-lens m)))
