(ns canvas.architecture.projection.overview
  "Self-spec: the two SYSTEM-MAP projections. `overview` (`fukan.canvas.projection.overview`) renders
   the SUBJECT view — the faculty map + flow loop, the canvas front door; `architecture`
   (`fukan.canvas.projection.architecture`) renders its code-side dual — subsystems, their
   faculty-annotated modules, and the `:may-depend` DAG. Both are pure graph→text projections over
   the kernel's shared `StructureDb`, realizing the use-side `Projection` faculty; both are leaves
   (no cross-module calls — they read lib vocab + the graph directly)."
  (:require [lib.code :refer [Operation Module]]
            [canvas.architecture.kernel.substrate :as substrate]
            [canvas.subject :as subj]))

(Module overview
  "The system overview — the subject faculty map + flow loop, rendered from the held model."
  {:realizes subj/Projection}                    ; faculty role: projects the subject view to text
  (Operation system-overview "Render the subject faculty map + flow loop from the held model."
    {:signature [:=> [:catn [:model substrate/StructureDb]] :string]}))

(Module architecture
  "The code-side architecture overview — subsystems, their faculty-annotated modules, and the :may-depend DAG."
  {:realizes subj/Projection}                    ; faculty role: projects the code-side view to text
  (Operation architecture-overview "Render the subsystem clustering + the :may-depend DAG from the held model."
    {:signature [:=> [:catn [:model substrate/StructureDb]] :string]}))
