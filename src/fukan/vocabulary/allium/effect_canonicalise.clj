(ns fukan.vocabulary.allium.effect-canonicalise
  "§3.8.4 four-pattern matcher: recognises Bool Expressions of canonical
   shape (post.X.f = E, post.X = T.created(...), not exists post.X,
   emitted(E, args...)) and produces matching Effect records.

   Inputs come from fukan.vocabulary.allium.expression/parse — see
   Task 2 for the AST shape this pattern-matches on.

   AST shapes (after Task 2 parser fixes):
     post.X.f  → Apply(\".\", [Apply(\".\", [Var(\"post\"), Var(\"X\")]), Var(\"f\")])
     not exists post.X → Apply(\"not-exists\", [Apply(\".\", [Var(\"post\"), Var(\"X\")])])
     T.created(args) → Apply(\"call\", [Apply(\".\", [Var(T), Var(\"created\")]), args...])
     emitted(E, args) → Apply(\"emitted\", [Var(E), args...])"
  (:require [fukan.model.effect :as fx]
            [fukan.model.relations :as r]))

;; ---------------------------------------------------------------------------
;; Navigation-chain helpers
;; ---------------------------------------------------------------------------

(defn- dot-apply? [form]
  (and (= :expr/apply (:case form)) (= "." (:op form))))

(defn- var-name [expr]
  (when (= :expr/var (get-in expr [:form :case]))
    (get-in expr [:form :name])))

(defn- post-nav-chain
  "Unfold a left-nested Apply(\".\", ...) chain rooted at Var(\"post\").
   Returns a vector of segment names after \"post\", e.g. [\"order\" \"total\"]
   for post.order.total, or nil if not a post-rooted chain."
  [expr]
  ;; Walk the left-nested dot chain collecting right-side names in a stack,
  ;; then verify the leftmost leaf is Var(\"post\").
  (loop [e expr, segs ()]
    (let [form (:form e)]
      (if (dot-apply? form)
        (let [[left right] (:args form)
              seg (var-name right)]
          (if seg
            (recur left (cons seg segs))
            nil))
        ;; Reached the bottom — must be Var("post")
        (when (= "post" (var-name e))
          (vec segs))))))

;; ---------------------------------------------------------------------------
;; Pattern recognizers
;; ---------------------------------------------------------------------------

(defn- created-call?
  "If the expression is a call of shape T.created(args...) — parsed as
   Apply(\"call\", [Apply(\".\", [Var(T), Var(\"created\")]), arg1, ...]) —
   return the type name string; else nil."
  [expr]
  (let [form (:form expr)]
    (when (and (= :expr/apply (:case form))
               (= "call" (:op form)))
      (let [args (:args form)
            callee (first args)]
        (when callee
          (let [callee-form (:form callee)]
            (when (and (= :expr/apply (:case callee-form))
                       (= "." (:op callee-form)))
              (let [[obj method] (:args callee-form)]
                (when (= "created" (var-name method))
                  (var-name obj))))))))))

(defn- emitted-call
  "If the expression is emitted(E, args...) — parsed as
   Apply(\"emitted\", [Var(E), arg1, ...]) — return [event-name args-vec];
   else nil."
  [expr]
  (let [form (:form expr)]
    (when (and (= :expr/apply (:case form))
               (= "emitted" (:op form)))
      (let [args (:args form)
            event-expr (first args)]
        (when-let [ename (var-name event-expr)]
          [ename (vec (rest args))])))))

;; ---------------------------------------------------------------------------
;; Public entry point
;; ---------------------------------------------------------------------------

(defn canonicalise
  "Given a Bool Expression and a source ExprId, return an Effect record if
   the Expression matches one of the §3.8.4 patterns; else nil.

   Patterns (in evaluation order):
     1. Apply(\"not-exists\", [post.X])            → :effect/destroy
     2. Apply(\"emitted\", [E, args...])            → :effect/emit
     3. Apply(\"=\", [post.X, T.created(...)])      → :effect/create
     4. Apply(\"=\", [post.X.f, rhs])              → :effect/write"
  [expr source-expr-id]
  (let [form (:form expr)]
    (cond
      ;; Destroy: not-exists post.X
      ;; Parser collapses `not exists X` into Apply(\"not-exists\", [X]) — see Task 2.
      (and (= :expr/apply (:case form))
           (= "not-exists" (:op form)))
      (let [arg (first (:args form))]
        (when-let [chain (post-nav-chain arg)]
          (when (= 1 (count chain))
            (fx/make-effect :effect/destroy
                            (r/primitive-ref (first chain))
                            nil
                            source-expr-id))))

      ;; Emit: emitted(E, args...)
      (and (= :expr/apply (:case form))
           (= "emitted" (:op form)))
      (when-let [[event-name args] (emitted-call expr)]
        (fx/make-effect :effect/emit
                        (r/primitive-ref event-name)
                        (cond
                          (empty? args)   nil
                          (= 1 (count args)) (first args)
                          :else ((requiring-resolve 'fukan.model.expression/make-apply)
                                 "tuple" args))
                        source-expr-id))

      ;; Write and Create: Apply("=", [post.X.f?, rhs])
      (and (= :expr/apply (:case form))
           (= "=" (:op form)))
      (let [[lhs rhs] (:args form)]
        (when-let [chain (post-nav-chain lhs)]
          (cond
            ;; Create: post.X = T.created(...) — single-segment chain + created-call rhs
            (and (= 1 (count chain))
                 (created-call? rhs))
            (fx/make-effect :effect/create
                            (r/primitive-ref (first chain))
                            rhs
                            source-expr-id)

            ;; Write: post.X.f = E — multi-segment chain (container = first, field = last)
            (>= (count chain) 2)
            (let [container (first chain)
                  field     (last chain)]
              (fx/make-effect :effect/write
                              (r/substrate-address container
                                                   [{:slot "field" :key field}])
                              rhs
                              source-expr-id))

            ;; Single-segment, non-created rhs — not a recognised effect pattern
            :else nil)))

      ;; No pattern matched
      :else nil)))
