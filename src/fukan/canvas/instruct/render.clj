(ns fukan.canvas.instruct.render
  "Structured instruction → markdown rendering.

   Each scenario produces a structured instruction map carrying its own
   `:rendered` markdown body — the canonical surface the implementing
   LLM consumes. This namespace exposes:

   * `to-markdown` — the substrate-level accessor (returns the
     instruction's `:rendered` string). Mirrors `project/render`'s
     entry-point shape so consumers can render Layer-A specs and Layer-B
     instructions through the same handle.

   * `section`, `bulleted`, `fenced` — small composable helpers each
     scenario's `:render` fn uses to assemble its markdown sections
     without sprinkling string-template fragments across the codebase.
     Keeping these here makes the markdown idioms (blank-line spacing,
     fence syntax) consistent across scenarios."
  (:require [clojure.string :as str]))

(defn to-markdown
  "Return the rendered markdown for an instruction map. Canonical accessor
   for callers that don't need the structured `:code-spec` /
   `:scenario-context` — the rendered string is what the implementing LLM
   reads."
  [{:keys [rendered]}]
  (or rendered ""))

(defn section
  "Join non-blank string blocks with double-newlines. nil and blank
   strings are skipped — useful for assembling optional sections without
   conditional branching in the caller."
  [& blocks]
  (->> blocks
       (remove nil?)
       (map str)
       (remove str/blank?)
       (str/join "\n\n")))

(defn bulleted
  "Render `items` as a markdown bullet list. Empty or nil input renders
   to the empty string."
  [items]
  (if (seq items)
    (str/join "\n" (map #(str "- " %) items))
    ""))

(defn fenced
  "Wrap `body` in a fenced code block tagged with `lang`."
  [lang body]
  (str "```" lang "\n" body "\n```"))
