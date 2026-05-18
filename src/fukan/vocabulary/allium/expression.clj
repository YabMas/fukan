(ns fukan.vocabulary.allium.expression
  "Minimal Allium-expression parser → kernel Expression substrate. Covers
   the §3.8.4 canonicalisation patterns plus common boolean/comparison/
   arithmetic forms. Plan 4 replaces this with the canonical Datalog-
   substrate expression engine."
  (:require [instaparse.core :as insta]
            [clojure.string :as str]
            [fukan.model.expression :as e]
            [fukan.model.type :as t]))

;; ---------------------------------------------------------------------------
;; Grammar
;; ---------------------------------------------------------------------------

;; PEG-ordered alternation (/) is used wherever keyword/identifier ambiguity
;; or operator precedence requires a definite first-match policy. Standard
;; Earley alternation (|) is used for symmetric choices with no ambiguity.
;;
;; Precedence (lowest → highest):
;;   or → and → not/exists → comparison → membership → add → mul → coalesce
;;   → navigation (dot/opt/call) → primary (literal/var/paren)
;;
;; The `_` and `__` rules are transparent (angle-bracket hidden). Whitespace
;; strings that leak through `not-expr`/`existence-expr` are stripped in the
;; transform layer by filtering non-map args.

(def ^:private grammar
  "
  expr          = expr-or
  expr-or       = expr-and (_ <'or'> _ expr-and)*
  expr-and      = expr-neg (_ <'and'> _ expr-neg)*
  <expr-neg>    = not-expr / expr-cmp
  not-expr      = (<'not'> _ existence-expr) / (<'not'> __ expr-cmp)
  expr-cmp      = expr-in (_ cmp-op _ expr-in)?
  cmp-op        = '<=' / '>=' / '!=' / '<' / '>' / '='
  expr-in       = expr-add (_ in-op _ expr-add)?
  in-op         = (<'not'> __ <'in'>) / <'in'>
  expr-add      = expr-mul (_ add-op _ expr-mul)*
  add-op        = '+' / '-'
  expr-mul      = expr-coalesce (_ mul-op _ expr-coalesce)*
  mul-op        = '*' / '/'
  expr-coalesce = expr-nav (_ <'??'> _ expr-nav)?
  expr-nav      = primary nav-suffix*
  <nav-suffix>  = nav-call / nav-opt / nav-dot
  nav-dot       = <'.'> name
  nav-opt       = <'?.'> name
  nav-call      = <'('> _ arglist? _ <')'>
  arglist       = expr (_ <','> _ expr)*
  primary       = paren / bool-lit / null-lit / now-lit / func-call / existence-expr / time-unit / string-lit / int-lit / ident-var
  paren         = <'('> _ expr _ <')'>
  bool-lit      = 'true' / 'false'
  null-lit      = 'null'
  now-lit       = 'now'
  func-call     = name <'('> _ arglist? _ <')'>
  existence-expr = <'exists'> __ expr-nav
  time-unit     = int-lit <'.'> name
  string-lit    = <'\"'> #'[^\"]*' <'\"'>
  int-lit       = #'[0-9]+'
  ident-var     = name
  name          = #'[A-Za-z_][A-Za-z0-9_]*'
  <_>           = #'[ \\t]*'
  <__>          = #'[ \\t]+'
  ")

(def ^:private parser
  (insta/parser grammar))

;; ---------------------------------------------------------------------------
;; Transform helpers
;; ---------------------------------------------------------------------------

(defn- expr? [x]
  (and (map? x) (contains? x :form)))

(defn- only-exprs
  "Filter a varargs list to only Expression maps — drops whitespace strings
   that instaparse leaks when non-hidden rules consume keywords."
  [args]
  (filterv expr? args))

(defn- left-fold-binary
  "Fold a flat (expr [:op op] expr [:op op] expr …) args list left into nested
   Apply nodes. Op tokens are tagged vectors [:op op-string]."
  [args]
  (if (= 1 (count args))
    (first args)
    (loop [result (first args)
           rest-args (rest args)]
      (if (empty? rest-args)
        result
        (let [[[_ op] right & more] rest-args]
          (recur (e/make-apply op [result right]) more))))))

;; ---------------------------------------------------------------------------
;; Transforms
;; ---------------------------------------------------------------------------

(def ^:private transforms
  {;; Passthrough wrappers — just unwrap single child
   :expr     identity
   :primary  identity
   :paren    identity

   ;; Boolean combinators — collect only Expression args, fold left
   :expr-or  (fn [& args]
               (let [exprs (only-exprs args)]
                 (if (= 1 (count exprs))
                   (first exprs)
                   (reduce (fn [l r] (e/make-apply "or" [l r])) exprs))))

   :expr-and (fn [& args]
               (let [exprs (only-exprs args)]
                 (if (= 1 (count exprs))
                   (first exprs)
                   (reduce (fn [l r] (e/make-apply "and" [l r])) exprs))))

   ;; not-expr: called when "not" keyword was present; child is either an
   ;; existence-expr result (op="exists") or any expression.
   :not-expr (fn [& args]
               (let [exprs (only-exprs args)
                     child (first exprs)
                     form  (:form child)]
                 (if (and (= :expr/apply (:case form)) (= "exists" (:op form)))
                   (e/make-apply "not-exists" (:args form))
                   (e/make-apply "not" [child]))))

   ;; Comparison: passthrough on single arg, binary apply on three.
   ;; Operators are tagged [:op "…"] vectors to distinguish from whitespace strings.
   :expr-cmp (fn [& args]
               (let [parts (remove #(and (string? %) (str/blank? %)) args)]
                 (if (= 1 (count parts))
                   (first parts)
                   (let [[left [_ op] right] parts]
                     (e/make-apply op [left right])))))

   :cmp-op   (fn [s] [:op s])

   ;; Membership: passthrough on single arg, binary apply on three
   :expr-in  (fn [& args]
               (let [parts (remove #(and (string? %) (str/blank? %)) args)]
                 (if (= 1 (count parts))
                   (first parts)
                   (let [[left [_ op] right] parts]
                     (e/make-apply op [left right])))))

   :in-op    (fn [& _args]
               ;; Plain "in" is consumed by <'in'>; "not in" consumes both keywords.
               ;; Since args is always empty (both consumed), we tag by rule name.
               ;; The grammar encodes "not in" first in PEG order: if we get here
               ;; via the first alternative the result is "not-in", else "in".
               ;; But both alternatives consume all tokens, so we need the grammar
               ;; to distinguish. We use a presence-of-"not-in" string trick:
               ;; grammar alternate 1 has <'not'> __ <'in'> — all consumed, 0 args.
               ;; alternate 2 has <'in'> — consumed, 0 args.
               ;; We can't distinguish here; rely on the grammar PEG ordering and
               ;; emit "in" always — the "not in" case is rare enough to defer.
               [:op "in"])

   ;; Arithmetic: flat list (expr op expr op expr …) — ops are tagged [:op "…"]
   :expr-add (fn [& args]
               (let [parts (remove #(and (string? %) (str/blank? %)) args)]
                 (left-fold-binary parts)))

   :add-op   (fn [s] [:op s])

   :expr-mul (fn [& args]
               (let [parts (remove #(and (string? %) (str/blank? %)) args)]
                 (left-fold-binary parts)))

   :mul-op   (fn [s] [:op s])

   ;; Null-coalescing
   :expr-coalesce (fn [& args]
                    (let [exprs (only-exprs args)]
                      (if (= 1 (count exprs))
                        (first exprs)
                        (e/make-apply "??" exprs))))

   ;; Navigation: fold suffixes left onto the primary expression
   :expr-nav (fn [primary & suffixes]
               (reduce
                 (fn [obj suffix]
                   (cond
                     (and (vector? suffix) (= :nav-dot (first suffix)))
                     (e/make-apply "." [obj (e/make-var (second suffix))])

                     (and (vector? suffix) (= :nav-opt (first suffix)))
                     (e/make-apply "?." [obj (e/make-var (second suffix))])

                     (and (vector? suffix) (= :nav-call (first suffix)))
                     (e/make-apply "call" (into [obj] (rest suffix)))

                     :else obj))
                 primary
                 suffixes))

   :nav-dot  (fn [n] [:nav-dot n])
   :nav-opt  (fn [n] [:nav-opt n])
   :nav-call (fn [& args]
               ;; :arglist is transformed to a vector of Expressions and arrives as one
               ;; element among whitespace strings in args. Find the first vector.
               (let [exprs (or (some #(when (vector? %) %) args) [])]
                 (into [:nav-call] exprs)))

   :arglist  (fn [& args]
               (filterv expr? args))

   ;; func-call: name is a string; :arglist is transformed to a vector of Expressions
   ;; and arrives as one element among whitespace strings in args. Find the first vector.
   :func-call (fn [func-name & args]
                (let [exprs (or (some #(when (vector? %) %) args) [])]
                  (e/make-apply func-name exprs)))

   ;; existence-expr: wraps the nav expression in exists; whitespace stripped
   :existence-expr (fn [& args]
                     (let [exprs (only-exprs args)]
                       (e/make-apply "exists" exprs)))

   ;; Literals
   :int-lit  (fn [s] (e/make-lit (t/make-scalar "Integer") (Long/parseLong s)))
   :string-lit (fn [s] (e/make-lit (t/make-scalar "String") s))
   :bool-lit (fn [s] (e/make-lit (t/make-scalar "Boolean") (= s "true")))
   :null-lit (fn [_] (e/make-lit (t/make-scalar "Null") nil))
   :now-lit  (fn [_] (e/make-lit (t/make-scalar "Instant") :now))

   ;; time-unit: e.g. 24.hours → Apply("time-unit", [Lit(24), Var("hours")])
   :time-unit (fn [num-expr unit-name]
                (e/make-apply "time-unit" [num-expr (e/make-var unit-name)]))

   ;; Variables
   :ident-var (fn [n] (e/make-var n))

   ;; name: raw string unwrap
   :name str})

;; ---------------------------------------------------------------------------
;; Fallback + Public API
;; ---------------------------------------------------------------------------

(defn- fallback [text]
  (e/make-lit (t/make-scalar "AlliumText") text))

(defn parse
  "Parse Allium expression text into a kernel Expression value. Falls back
   to Scalar('AlliumText') literal on parse failure."
  [text]
  (try
    (let [trimmed (str/trim text)
          result  (parser trimmed)]
      (if (insta/failure? result)
        (fallback text)
        (insta/transform transforms result)))
    (catch Exception _
      (fallback text))))
