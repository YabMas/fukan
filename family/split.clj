(ns family.split
  "A HYPOTHESIS, stated where fukan can check it: that today's kernel (`src/fukan`) already divides
   into the libraries a fukan family would ship.

   Not part of fukan's self-model and not discovered with it (`canvas/` is the default spec dir).
   Run it on purpose:

     clojure -M:dev -m fukan.cli check --src src --spec-dirs family --format text

   The lines below are one reading of the split and are meant to be redrawn. What a redrawn line
   costs is what `check` then reports: every namespace dependency crossing it the wrong way.

   WHAT THIS CAN AND CANNOT SHOW. A Region claims namespaces, so the check sees a dependency
   between two files and nothing inside one. A namespace-clean answer here is necessary and far
   from sufficient: the code-structure concepts that live INSIDE core files — `correspond` in
   `structure.clj`, the design/extracted strata in `substrate.clj` and `rules.clj`, the stamping
   of extracted roots in `cozo/build.clj` — cross no namespace and are invisible to it."
  (:require [fukan.common.vocab.code.region :refer [Region]]))

(Region Core
  "The graph and the node over the Cozo db, and the authoring surface: the substrate, the
   assembler, `defstructure` with its laws and relations, the query and law engines, and the
   discovery of authored specs. Depends on nothing else in the family."
  {:prefix ["fukan.canvas.core." "fukan.cozo." "fukan.canvas.ingestion."]})

(Region Inspect
  "Reading a model back: the print-duals, the prose and design projections, the view graph and
   the explorer that serves it."
  {:prefix     ["fukan.canvas.projection." "fukan.web."]
   :may-depend [Core]})

(Region CodeStructure
  "Representing CODE on the graph: the extraction plug-point, the pipeline merging extracted
   code onto the authored design, and the composition root naming a concrete extractor."
  {:prefix     ["fukan.model." "fukan.infra."]
   :may-depend [Core]})

(Region Cockpit
  "The entries a person or a program drives it through. Composes everything, owned by nothing."
  {:prefix     ["fukan.cli" "fukan.repl" "fukan.core"]
   :may-depend [Core Inspect CodeStructure]})
