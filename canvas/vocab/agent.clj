(ns canvas.vocab.agent
  "The fukan-on-fukan model's COMPOSER layer — the Agent surface. The Agent composes its
   OWN tools from the primitives fukan provides: lenses (focuses) and the two acts (probe
   = read → finding, project = render → artifact). A `Tool` is a composed capability — it
   probes some focuses and/or projects through some, BUNDLING several or CHAINING a probe
   into a projection, into something no single faculty act is. This is the LLM-tool-use
   surface inverted: instead of being handed hardcoded tools, the agent BUILDS its toolset
   from model primitives.

   Vocab-only canvas spec (no build-canvas)."
  (:require [fukan.canvas.core.structure :refer [defstructure]]))

(defstructure Tool
  "A capability the agent composes from primitives to answer a question — it probes some
   focuses (read → findings) and/or projects through some (render → artifacts). Bundling
   or chaining them is how the agent builds a tool the faculties don't provide alone."
  (slot :doc      (optional :String))
  (slot :answers  (one :String))      ; the question this tool answers
  (slot :probes   (many Lens))        ; focuses it reads (observe → findings)
  (slot :projects (many Lens))        ; focuses it renders through (→ artifacts)
  ;; an empty tool composes nothing — a tool must put at least one primitive to work
  (law "a tool composes at least one primitive"
    :offenders '[?t]
    :where '[(not [?r :rel/from ?t] [?r :rel/kind :probes])
             (not [?r2 :rel/from ?t] [?r2 :rel/kind :projects])]))
