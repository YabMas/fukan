(ns fukan.canvas.instruct.core
  "Scenario substrate — the scenario contract + validators.

   A *scenario* wraps a Layer-A projection (`fukan.canvas.project.*`) with
   situation-specific framing for the implementing LLM. Layer B of
   Phase 7's two-layer architecture; Layer A produces the deterministic
   code spec; Layer B carries the prose envelope, neighbor context, and
   discipline reminders that turn one raw spec into a full instruction.

   Two contracts live here:

   * **Scenario declaration** — what every `def scenario` form satisfies.
     Mirrors the Phase 5 lens contract in shape (id + description +
     prompt-fragment + render) but adds an explicit `:build-context` step
     so scenarios produce a structured-plus-rendered instruction map
     rather than a free-form string.

     {:scenario-id     <qualified kw>  ; :code-side/<name> in Phase 7
      :description     <string>        ; one-line summary surfaced in (help)
      :prompt-fragment <string>        ; situation-framing prose template
      :build-context   <fn>            ; (fn [code-spec opts] -> ctx-map)
      :render          <fn>}           ; (fn [code-spec ctx opts] -> instruction)

   * **Instruction return shape** — what scenarios produce:

     {:scenario-id      <qualified kw>
      :code-spec        <Layer-A projection map>
      :scenario-context <map>
      :rendered         <string>}

   The `:rendered` field is canonical — the implementing LLM consumes
   it. `:code-spec` and `:scenario-context` round-trip for downstream
   tooling (Phase 8's auto-dispatch will parse them).

   See DESIGN.md § \"Scenario composition (Layer B)\" (original design:
   doc/plans/2026-05-27-scenario-handoff-design.md, git history).")

(defn validate-scenario
  "Return a vector of issue strings describing why m is not a valid
   scenario declaration. An empty vector means m satisfies the contract."
  [m]
  (let [issues (transient [])]
    (when-not (map? m)
      (conj! issues (str "scenario must be a map; got " (pr-str (type m)))))
    (when (map? m)
      (when-not (qualified-keyword? (:scenario-id m))
        (conj! issues (str ":scenario-id must be a qualified keyword; got "
                           (pr-str (:scenario-id m)))))
      (when-not (string? (:description m))
        (conj! issues (str ":description must be a string; got "
                           (pr-str (:description m)))))
      (when-not (string? (:prompt-fragment m))
        (conj! issues (str ":prompt-fragment must be a string; got "
                           (pr-str (:prompt-fragment m)))))
      (when-not (fn? (:build-context m))
        (conj! issues (str ":build-context must be a fn; got "
                           (pr-str (:build-context m)))))
      (when-not (fn? (:render m))
        (conj! issues (str ":render must be a fn; got "
                           (pr-str (:render m))))))
    (persistent! issues)))

(defn valid-scenario?
  "True iff m satisfies the scenario declaration contract."
  [m]
  (empty? (validate-scenario m)))

(defn validate-instruction
  "Return a vector of issue strings describing why m is not a valid
   instruction (the map a scenario's :render fn returns). An empty
   vector means m satisfies the contract."
  [m]
  (let [issues (transient [])]
    (when-not (map? m)
      (conj! issues (str "instruction must be a map; got " (pr-str (type m)))))
    (when (map? m)
      (when-not (qualified-keyword? (:scenario-id m))
        (conj! issues (str ":scenario-id must be a qualified keyword; got "
                           (pr-str (:scenario-id m)))))
      (when-not (map? (:code-spec m))
        (conj! issues (str ":code-spec must be a map; got "
                           (pr-str (:code-spec m)))))
      (when-not (map? (:scenario-context m))
        (conj! issues (str ":scenario-context must be a map; got "
                           (pr-str (:scenario-context m)))))
      (when-not (string? (:rendered m))
        (conj! issues (str ":rendered must be a string; got "
                           (pr-str (:rendered m))))))
    (persistent! issues)))

(defn valid-instruction?
  "True iff m satisfies the instruction return-shape contract."
  [m]
  (empty? (validate-instruction m)))
