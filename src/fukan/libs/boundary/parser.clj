(ns fukan.libs.boundary.parser
  "Instaparse-based parser for .boundary specification files.
   Converts Boundary text into a Clojure AST suitable for analyzer
   consumption (Plan 3b).

   Two file shapes:
   - Module-bound: sibling to a .allium file at the same coordinate;
     carries fn declarations, exports:, and use.
   - Subsystem-bound: standalone composite; carries one subsystem block
     plus use.

   Grammar follows the Allium parser's idioms. Expression bodies inside
   fn `triggers:` / `returns:` clauses are captured as text or simple
   references — the Plan 4 expression parser will type them."
  (:require [instaparse.core :as insta]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Grammar
;; ---------------------------------------------------------------------------

(def grammar
  "PEG grammar for the .boundary specification language."

  "
  (* ============ Top-level ============ *)

  boundary-file = _ header _ declarations _

  header = <'-- boundary:'> _ version-number
  version-number = #'[0-9]+'

  declarations = (declaration _)*
  declaration = use-decl / fn-decl / exports-decl / subsystem-decl

  (* ============ Use ============ *)

  use-decl = <'use'> __ quoted-path __ <'as'> __ ident

  quoted-path = <'\"'> path-content <'\"'>
  path-content = #'[^\"]*'

  ident = #'[a-zA-Z_][a-zA-Z0-9_]*'

  (* ============ Fn — three forms ============

     Ordered alternation: the foreign-attach form has the most
     discriminating token (a '/'), so it's tried first. local-attach
     ('.' separator, no parens) is tried next, falling through to the
     classic declare-new form (parenthesised parameter list). *)

  fn-decl = fn-foreign-attach / fn-local-attach / fn-declare-new

  fn-declare-new = <'fn'> __ ident _ <'('> _ params? _ <')'> (_ return-type)? prose? _ fn-body?

  fn-local-attach = <'fn'> __ ident <'.'> ident _ prose? _ fn-body

  fn-foreign-attach = <'fn'> __ ident <'/'> ident <'.'> ident _ prose? _ fn-body

  params = param (_ <','> _ param)*
  param = ident _ <':'> _ type-ref

  return-type = <'->'> _ type-ref

  (* Prose lines: indented '--' lines immediately following a fn.
     Leading-whitespace requirement disambiguates fn prose from
     top-level comments. Mirrors Allium's annotation-trailing-prose.
     No `_` precedes prose: the prose-line regex consumes the leading
     newline + indentation itself, so a greedy `_` must not eat it. *)
  prose = (prose-line)+
  prose-line = <#'[ \\t]*\\n[ \\t]+--[ \\t]?'> #'[^\\n]*'

  (* ============ Fn body — optional behavioural attachment ============ *)

  fn-body = <'{'> _ fn-body-clause* _ <'}'>
  fn-body-clause = triggers-clause / returns-clause
  triggers-clause = <'triggers:'> _ rule-ref _
  returns-clause = <'returns:'> _ returns-text _

  rule-ref = qualified-rule-ref / simple-rule-ref
  simple-rule-ref = ident
  qualified-rule-ref = ident <'/'> ident

  (* returns-text: single-line capture — the rest of the line after
     'returns:' is the expression. Multi-line expressions can be added
     in Plan 4 when the constraint-language expression parser arrives. *)
  returns-text = #'[^\\n}]+'

  (* ============ Exports (module-bound) ============ *)

  exports-decl = <'exports:'> _ export-entry*
  export-entry = (qualified-export / simple-export) _
  simple-export = ident
  qualified-export = ident <'.'> ident

  (* ============ Subsystem ============ *)

  subsystem-decl = <'subsystem'> __ ident _ <'{'> _ subsystem-body _ <'}'>

  subsystem-body = contains-clause _ subsystem-exports-clause _ subsystem-rules-clause?

  contains-clause = <'contains:'> _ contains-entry*
  contains-entry = path _
  path = #'[^\\s]+'

  subsystem-exports-clause = <'exports:'> _ subsystem-export-entry*
  subsystem-export-entry = (qualified-subsystem-export / simple-subsystem-export) _
  simple-subsystem-export = ident
  qualified-subsystem-export = ident <'/'> (qualified-export / simple-export)

  subsystem-rules-clause = <'rules:'> _ rule-entry*
  rule-entry = ident _ <'('> _ rule-args? _ <')'> _
  rule-args = rule-arg (_ <','> _ rule-arg)*
  rule-arg = ident _ <':'> _ rule-arg-value
  rule-arg-value = #'[^,)\\n]+'

  (* ============ Type references ============ *)

  type-ref = generic-type / optional-type / qualified-type / simple-type

  generic-type = ident <'<'> _ type-ref-list _ <'>'>
  optional-type = (generic-type / qualified-type / simple-type) <'?'>
  qualified-type = ident <'/'> ident
  simple-type = ident
  type-ref-list = type-ref (_ <','> _ type-ref)*

  (* ============ Whitespace / comments ============ *)

  <_> = (whitespace / comment)*
  <__> = (whitespace / comment)+
  <whitespace> = <#'\\s+'>
  <comment> = <#'--[^\\n]*'>
  ")

(def boundary-parser
  (insta/parser grammar))

;; ---------------------------------------------------------------------------
;; Transform map (built up across tasks)
;; ---------------------------------------------------------------------------

(def transforms
  {:version-number   #(Integer/parseInt %)
   :header           (fn [v] {:boundary-version v})
   :declarations     (fn [& ds] (vec ds))
   :declaration      identity
   :ident            identity
   :path-content     identity
   :quoted-path      identity
   :use-decl         (fn [path alias]
                       {:type :use :path path :alias alias})
   :simple-type      (fn [n] {:kind :simple :name n})
   :qualified-type   (fn [ns n] {:kind :qualified :ns ns :name n})
   :optional-type    (fn [inner] {:kind :optional :inner inner})
   :generic-type     (fn [name params]
                       ;; `params` arrives as the already-vectorised result of
                       ;; the `type-ref-list` rule — don't wrap it again.
                       {:kind :generic :name name :params params})
   :type-ref-list    (fn [& ts] (vec ts))
   :type-ref         identity
   :return-type      identity
   :param            (fn [name type-ref] {:name name :type-ref type-ref})
   :params           (fn [& ps] (vec ps))
   :prose-line       identity
   :prose            (fn [& lines] (str/join "\n" (map str/trim lines)))
   :simple-rule-ref      (fn [n] {:kind :local :name n})
   :qualified-rule-ref   (fn [ns n] {:kind :qualified :ns ns :name n})
   :rule-ref             identity
   :triggers-clause      (fn [ref] [:triggers ref])
   :returns-text         (fn [s] (str/trim s))
   :returns-clause       (fn [text] [:returns text])
   :fn-body-clause       identity
   :fn-body              (fn [& clauses]
                           (reduce (fn [body [k v]] (assoc body k v))
                                   {:triggers nil :returns nil}
                                   clauses))
   :fn-decl           identity   ; passthrough; variants below do the work

   :fn-declare-new    (fn [name & rest]
                        (let [ps    (or (some #(when (vector? %) %) rest) [])
                              ret   (some #(when (and (map? %)
                                                      (contains? % :kind))
                                             %) rest)
                              body  (some #(when (and (map? %)
                                                      (contains? % :triggers))
                                             %) rest)
                              prose (some #(when (string? %) %) rest)]
                          {:type :fn
                           :form :declare-new
                           :name name
                           :params ps
                           :return-type ret
                           :prose prose
                           :body body}))

   :fn-local-attach   (fn [contract op & rest]
                        (let [body  (some #(when (and (map? %)
                                                      (contains? % :triggers))
                                             %) rest)
                              prose (some #(when (string? %) %) rest)]
                          {:type :fn
                           :form :local-attach
                           :contract contract
                           :op op
                           :prose prose
                           :body body}))

   :fn-foreign-attach (fn [alias contract op & rest]
                        (let [body  (some #(when (and (map? %)
                                                      (contains? % :triggers))
                                             %) rest)
                              prose (some #(when (string? %) %) rest)]
                          {:type :fn
                           :form :foreign-attach
                           :alias alias
                           :contract contract
                           :op op
                           :prose prose
                           :body body}))
   :simple-export     identity
   :qualified-export  (fn [c o] (str c "." o))
   :export-entry      identity
   :exports-decl      (fn [& entries] {:type :exports :entries (vec entries)})

   :path                       identity
   :contains-entry             identity
   :contains-clause            (fn [& paths] [:contains (vec paths)])
   :simple-subsystem-export    identity
   :qualified-subsystem-export (fn [alias rest] (str alias "/" rest))
   :subsystem-export-entry     identity
   :subsystem-exports-clause   (fn [& entries] [:exports (vec entries)])
   :rule-arg-value             (fn [v] (str/trim v))
   :rule-arg                   (fn [k v] {:key k :value v})
   :rule-args                  (fn [& args] (vec args))
   :rule-entry                 (fn [name & args]
                                 {:name name :args (or (first args) [])})
   :subsystem-rules-clause     (fn [& entries] [:rules (vec entries)])
   :subsystem-body             (fn [& clauses]
                                 (reduce (fn [body [k v]] (assoc body k v))
                                         {:contains [] :exports [] :rules []}
                                         clauses))
   :subsystem-decl             (fn [name body]
                                 (merge {:type :subsystem :name name} body))
   :boundary-file    (fn [header decls]
                       {:boundary-version (:boundary-version header)
                        :declarations decls})})

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn parse-boundary
  "Parse a .boundary specification string into an AST map.
   Returns a map with :boundary-version and :declarations on success,
   or an instaparse failure object on parse error."
  {:malli/schema [:=> [:cat :string] :map]}
  [text]
  (let [tree (boundary-parser text)]
    (if (insta/failure? tree)
      tree
      (insta/transform transforms tree))))

(defn parse-file
  "Parse a .boundary specification file into an AST map."
  {:malli/schema [:=> [:cat :string] :map]}
  [path]
  (parse-boundary (slurp path)))
