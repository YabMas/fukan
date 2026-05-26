(ns canvas.agent.query
  "Canvas port of agent/query.allium + query.boundary.

   Coverage:
     - value QueryForm    → construction/value (opaque; shape described in DSLShape)
     - value ParsedQuery  → construction/record (3 fields)
     - value QueryAtom    → construction/record (2 fields)
     - value QueryRow     → construction/value (opaque; row is a map from var → value)
     - fn parse           → construction/function
     - fn evaluate        → construction/function with cross-module edb ref
     - 3 invariants       → vocab.behavioral/invariant each
     - exports: QueryForm ParsedQuery QueryAtom QueryRow

   Notes:
     - QueryForm and QueryRow are opaque — no field decomposition.
     - ParsedQuery.in field uses (list-of :Keyword) — approximated as :Keyword.
     - Cross-module ref edb.EDB uses :edb/EDB."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function record value exports]]
            [fukan.canvas.vocab.behavioral :refer [invariant]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "agent.query"

      ;; ── Value Types ──────────────────────────────────────────────────────

      (value "QueryForm"
        "The raw caller form: [:find <var-or-aggregation> ... :where <atom> ...]
         Vectors only; variable names start with '?'. The keys :find and
         :where are required; :in is optional.")

      (record "ParsedQuery"
        "The internal AST produced by parse. find carries variable
         keywords (e.g. :?p); where carries QueryAtom records; in carries
         the optional input bindings."
        (field find  (list-of :Value))
        (field where (list-of :QueryAtom))
        (field in    (list-of :Keyword)))

      (record "QueryAtom"
        "One atom in the :where clause. predicate is the EDB predicate
         keyword; args carry the subject and remaining positional values
         with variables rewritten to keyword form."
        (field predicate :Keyword)
        (field args      (list-of :Value)))

      (value "QueryRow"
        "One result row: a map from :find variable keyword to bound value.")

      ;; ── Invariants ────────────────────────────────────────────────────────

      (invariant "DSLShape"
        "A valid query form is a vector containing :find and :where
         section markers. Each :where atom is a vector of at least two
         elements: subject and predicate, optionally followed by more
         positional arguments. Variables are symbols whose name starts
         with '?'; everything else is a literal pattern matched against
         EDB tuples."
        (holds-that "dsl-shape-contract"))

      (invariant "UnificationSemantics"
        "Atoms are matched against EDB tuples positionally. Variable
         bindings established earlier in the :where clause must agree
         with later occurrences — re-binding the same variable to a
         different value rejects the row. Non-variable pattern arguments
         match by equality with the tuple value at the same position."
        (holds-that "positional-unification-semantics"))

      (invariant "ParseFailureModes"
        "parse signals shape errors via ex-info with typed :type values:
           :query-not-vector  -- top-level form is not a vector
           :missing-find      -- no :find section
           :missing-where     -- no :where section
           :malformed-atom    -- a :where atom is not a vector of length >= 2
         The sandbox surfaces these :type values through EvalResult's
         :error/kind so agents can match against them."
        (holds-that "parse-failure-typed-ex-info"))

      ;; ── Functions ─────────────────────────────────────────────────────────

      (function "parse"
        "Parse a [:find ... :where ...] form into the internal AST.
         Throws typed ex-info on shape errors: :query-not-vector,
         :missing-find, :missing-where, :malformed-atom."
        (takes [form :QueryForm])
        (gives :ParsedQuery))

      (function "evaluate"
        "Evaluate a parsed query against an EDB. Returns a vector of
         rows where each row maps :find var keywords to bound values."
        (takes [parsed :ParsedQuery
                edb    :edb/EDB])
        (gives (list-of :QueryRow)))

      ;; ── Exports ───────────────────────────────────────────────────────────

      (exports QueryForm ParsedQuery QueryAtom QueryRow))))
