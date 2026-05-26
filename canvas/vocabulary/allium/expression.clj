(ns canvas.vocabulary.allium.expression
  "Canvas port of vocabulary/allium/expression.allium.

   Scope: The Allium expression sub-substrate — precedence model,
   supported forms, and graceful failure mode.

   Coverage:
     - 5 invariants: PrecedenceOrder, SupportedPrimaries,
       CoversCanonicalisationPatterns, FallbackOnFailure, PureOfText"
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.vocab.behavioral :refer [invariant]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "vocabulary.allium.expression"

      (invariant "PrecedenceOrder"
        "Operator precedence, lowest to highest:
           or → and → not / exists → comparison → membership → add → mul
           → coalesce (??) → navigation (dot, opt-dot, call) → primary.
         Same-precedence operators fold left. Parentheses override."
        (holds-that "precedence-order-fixed-lowest-to-highest"))

      (invariant "SupportedPrimaries"
        "Recognised primary forms: parenthesised expressions, boolean
         literals (true, false), null, the `now` instant literal,
         integer and string literals, time-unit literals (24.hours),
         function calls (f(args)), existence assertions (exists X),
         and bare identifiers (variables). Identifiers that pair with a
         dotted navigation suffix become field accesses; the navigation
         chain produces nested Apply nodes."
        (holds-that "supported-primary-forms-enumerated"))

      (invariant "CoversCanonicalisationPatterns"
        "The parser produces AST shapes that the effect canonicaliser
         recognises for all four §3.8.4 patterns: post.X.f = E (write),
         post.X = T.created(...) (create), not exists post.X (destroy),
         emitted(E, args...) (emit). Extending the canonicaliser must
         not require parser changes for these patterns."
        (holds-that "parser-covers-all-canonicalisation-patterns"))

      (invariant "FallbackOnFailure"
        "Parse failure does not raise. The parser returns a kernel
         Lit(Scalar(AlliumText), <original-text>) so the analyzer can
         continue and the projection still surfaces the original text.
         This keeps a single malformed expression from halting the build."
        (holds-that "parse-failure-returns-fallback-lit"))

      (invariant "PureOfText"
        "Same input text always produces the same Expression. No global
         state, no caching, no I/O. The parser is a pure function of its
         string input."
        (holds-that "expression-parser-pure-of-text")))))
