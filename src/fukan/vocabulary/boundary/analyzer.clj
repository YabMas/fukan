(ns fukan.vocabulary.boundary.analyzer
  "Boundary AST → kernel content. Per MODEL.md §8.2.

   Two file shapes (Plan 3a parser AST):
   - Module-bound: declarations are mix of :use, :fn, :exports.
   - Subsystem-bound: declarations are use + one :subsystem.

   Task 2 adds the shape-dispatch and per-decl handlers (which is when
   fukan.model.build becomes a real require).

   This namespace is built up across Tasks 2-7.")

(defn analyze-file
  "Apply a parsed .boundary AST to the model. `coord` is the file's
   coordinate (relative path minus .boundary extension). `use-aliases`
   is the file-local map of alias → coord for cross-module resolution.

   Returns updated model. Tasks 2+ flesh out the handlers; for now,
   returns the model unchanged."
  [model _ast _coord _use-aliases]
  model)
