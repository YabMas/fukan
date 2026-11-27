(ns canvas.claims.signature-comparison
  "What the design of the multi-arity signature comparison commits to. The comparison lives in the
   malli dialect, which is no declared element of this self-model (canvas/architecture models src/
   only), so the claims are about cozo-law, the module whose evaluation they govern. Each Claim's
   name is the id the design record carries for it and its docstring is the record's statement."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.architecture.cozo.law :as law]))

(Claim no-negated-unrestricted-comparison
  "No negation in signature-differs' dependency cone reaches arrow-differs or arrow-agrees, or any rule that can relate two arrow schemas of which either is not an arity of a function schema: the multi-arity case decides arity agreement through a relation of its own whose every body leads with the pair of arities of two function schemas, and the general arrow relations stay outside that cone."
  {:about [law/cozo-law] :evidence "round"})

(Claim comparison-meaning-unchanged
  "arrow-differs, arrow-agrees and signature-differs hold of exactly the pairs they held of before the rewrite, so :correspondence/signature-disagrees reports the same offenders on every model."
  {:about [law/cozo-law] :evidence "round"})

(Claim signature-law-scales-with-pairs
  "The cost of evaluating :correspondence/signature-disagrees grows with the signature pairs it compares and with the square of the number of arities the model's multi-arity function schemas hold, never with the square of the distinct arrow schemas that are no such arity; a test over a synthetic model with on the order of a thousand distinct arrow signatures, only a few of them paired and few of them arities of a function schema, holds the law to a wall-clock far below the per-law budget."
  {:about [law/cozo-law] :evidence "round"})
