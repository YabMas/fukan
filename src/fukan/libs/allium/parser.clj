(ns fukan.libs.allium.parser
  "Instaparse-based parser for .allium specification files.
   Converts Allium text into a Clojure AST suitable for model building."
  (:require [instaparse.core :as insta]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Grammar
;; ---------------------------------------------------------------------------

(def grammar
  "PEG grammar for the Allium specification language.

   Two levels of parsing depth:
   1. Structural constructs (fully parsed): entities, values, variants, enums,
      external entities, use declarations, given blocks, field declarations
      with type references.
   2. Rule bodies (skeleton + text capture): when-clause triggers parsed into
      name + params; let/requires/ensures bodies captured as trimmed strings."

  "
  (* ============ Top-level ============ *)

  allium-file = _ header _ declarations _

  header = <'-- allium:'> _ version-number
  version-number = #'[0-9]+'

  declarations = (declaration _)*
  declaration = use-decl / given-block / enum-decl / open-question / config-block /
                deferred-decl / contract-decl / default-decl /
                external-entity / external-value / surface-decl / variant-decl /
                entity-decl / value-decl / rule-decl / invariant-decl /
                guarantee-decl / actor-decl

  (* ============ Use ============ *)

  use-decl = <'use'> __ quoted-path __ <'as'> __ ident

  quoted-path = <'\"'> path-content <'\"'>
  path-content = #'[^\"]*'

  (* ============ Given ============ *)

  given-block = <'given'> _ <'{'> _ given-bindings _ <'}'>
  given-bindings = (given-binding _ (<','> _)?)*
  given-binding = ident _ <':'> _ type-ref

  (* ============ Open Question ============ *)

  open-question = <'open'> __ <'question'> __ <'\"'> question-text <'\"'>
  question-text = #'[^\"]*'

  (* ============ Config Block ============ *)

  config-block = <'config'> _ <'{'> _ config-params _ <'}'>
  config-params = (config-param _)*
  config-param = ident _ <':'> _ type-ref (_ <'='> _ rest-of-line)?

  (* ============ Deferred ============ *)

  deferred-decl = <'deferred'> __ ident <'.'> ident

  (* ============ Contract ============ *)

  contract-decl = <'contract'> __ ident _ <'{'> _ field-list _ <'}'>

  (* ============ Default ============ *)

  default-decl = <'default'> __ ident __ ident _ <'='> _ <'{'> default-body <'}'>
  default-body = #'[^}]*'

  (* ============ Enum ============ *)

  enum-decl = <'enum'> __ ident _ description-string? _ <'{'> _ enum-values _ <'}'>
  enum-values = enum-value (_ <'|'>? _ enum-value)*
  enum-value = backtick-literal / ident
  backtick-literal = <'`'> #'[^`]+' <'`'>

  (* ============ External Entity ============ *)

  external-entity = <'external'> __ <'entity'> __ ident _ description-string? _ <'{'> external-body <'}'>
  external-body = #'[^}]*'

  external-value = <'external'> __ <'value'> __ ident _ description-string?

  (* ============ Surface ============ *)

  surface-decl = <'surface'> __ ident _ description-string? _ <'{'> _ field-list _ <'}'>

  (* ============ Entity / Value ============ *)

  entity-decl = <'entity'> __ ident _ description-string? _ <'{'> _ field-list _ <'}'>
  value-decl  = <'value'> __ ident _ description-string? _ <'{'> _ field-list _ <'}'>

  (* ============ Variant ============ *)

  variant-decl = <'variant'> __ ident _ description-string? _ <':'> _ type-ref _ <'{'> _ field-list _ <'}'>

  (* ============ Guarantee (module-level prose promise) ============ *)

  guarantee-decl = <'guarantee'> __ ident

  (* ============ Actor ============ *)

  actor-decl = <'actor'> __ ident _ <'{'> _ actor-body _ <'}'>
  actor-body = identified-by-clause _ within-clause? _
  identified-by-clause = <'identified_by'> _ <':'> _ type-ref
  within-clause = <'within'> _ <':'> _ type-ref

  (* ============ Invariant ============ *)

  invariant-decl = <'invariant'> __ ident _ description-string? _ <'{'> _ invariant-form _ <'}'>
  invariant-form = invariant-for-quantification / invariant-expression
  invariant-for-quantification = <'for'> __ ident __ <'in'> __ ident _ invariant-guard? _ <':'> _ invariant-assertion
  invariant-guard = <'where'> __ #'(?:(?!:[\\s]).)+(?=:[\\s])'
  invariant-assertion = invariant-chunk+
  invariant-expression = invariant-chunk+

  <invariant-chunk> = inv-brace-group / inv-paren-group / inv-text-chunk
  inv-brace-group = '{' invariant-chunk* '}'
  inv-paren-group = '(' invariant-chunk* ')'
  inv-text-chunk = #'[^{}()\\n]+' / eol

  (* ============ Description String ============ *)

  description-string = <'\"'> description-content <'\"'>
  description-content = #'[^\"]*'

  (* ============ Fields ============ *)

  field-list = (field-item _ (<','> _)?)*
  <field-item> = provides-block / related-block / exposes-block / contracts-block
               / nested-variant / invariant-decl / annotation / when-guard
               / facing-field / context-field / timeout-field / let-field
               / transitions-block
               / field-entry

  (* Exposes block: exposes: followed by dotted identifiers *)
  exposes-block = <'exposes'> _ <':'> _ exposes-entries
  exposes-entries = (dotted-ident _)+
  dotted-ident = #'[A-Za-z_][A-Za-z0-9_.]*'

  (* Contracts block: contracts: followed by demands/fulfils entries *)
  contracts-block = <'contracts'> _ <':'> _ contracts-entries
  contracts-entries = (contracts-entry _)+
  contracts-entry = contracts-verb __ contract-ref
  contracts-verb = 'demands' / 'fulfils'
  contract-ref = qualified-name / ident

  (* Surface-specific: facing role: Type, context role: Type *)
  facing-field = <'facing'> __ ident _ <':'> _ type-ref
  context-field = <'context'> __ ident _ <':'> _ type-ref

  (* Surface-specific: timeout: RuleName *)
  timeout-field = <'timeout'> _ <':'> _ ident

  (* Surface-specific: let name = expr *)
  let-field = <'let'> __ ident _ <'='> _ rest-of-line

  (* Annotation: @guarantee Name or @guidance
     Optional leading prose block: contiguous '--' lines immediately preceding '@'.
     annotation-prose is the block of prose-lines; annotation-mark is the '@' line.
     prose-line strips the leading '--' prefix and captures the raw content. *)
  annotation = annotation-prose? <#'[ \t]*'> annotation-mark
  annotation-prose = (prose-line)+
  prose-line = <#'[ \t]*--[ \t]?'> #'[^\n]*' <'\n'>
  annotation-mark = <'@'> ident (__ ident)?

  (* When guard: when condition — appears after provides entries *)
  when-guard = <'when'> __ when-guard-text
  when-guard-text = #'[^\\n}]+'

  (* Related block: related: followed by indented entries (structured) *)
  related-block = <'related'> _ <':'> _ related-entries
  related-entries = (related-entry related-entry-sep)*
  <related-entry-sep> = <#'[ \\t]*\\n'> _
  related-entry = ident related-entry-args?
  related-entry-args = <'('> _ #'[^)]*' _ <')'>
  field-entry = ident _ <':'> _ field-value

  (* Provides block: single 'provides:' with typed operation entries.
     Uses newline-based separation between entries so that inline
     comments are captured by provides-entry-comment, not swallowed
     by the line-comment rule in _.
     Each entry may carry an optional `when ...` guard on the next
     indented line — captured as provides-entry-when. *)
  provides-block = <'provides'> _ <':'> _ provides-entries
  provides-entries = (provides-entry provides-entry-sep)*
  <provides-entry-sep> = <#'[ \\t]*\\n'> _
  provides-entry = provides-entry-name provides-params? provides-return? provides-entry-when? provides-entry-comment?
  provides-entry-name = #'(?!when[ \\t\\n])[A-Za-z_][A-Za-z0-9_]*+(?![ \\t]*:)'
  provides-params = <'('> _ provides-param-list? _ <')'>
  provides-param-list = provides-param (_ <','> _ provides-param)*
  provides-param = ident <'?'>? (_ <':'> _ type-ref)?
  provides-return = <#'[ \\t]+'> <'->'> <#'[ \\t]+'> type-ref
  provides-entry-when = <#'[ \\t]*\\n[ \\t]+'> <'when'> __ provides-entry-when-text
  provides-entry-when-text = #'[^\\n]+'
  provides-entry-comment = <#'[ \\t]+--[ \\t]*'> inline-comment

  nested-variant = <'variant'> __ ident _ <'{'> _ field-list _ <'}'>

  transitions-block = <'transitions'> __ ident _ <'{'> _ transition-edges _ <'}'>
  transition-edges = (transition-edge _)*
  transition-edge = ident _ <'->'> _ ident

  (* Ordered alternation: try relationship, projection, typed-with-when, typed-with-comment, typed-field, then derived *)
  field-value = relationship / projection / typed-with-when / typed-with-comment / typed-field / derived-value

  typed-with-when = type-ref _ <'when'> _ <':'> _ rest-of-line

  relationship = type-ref __ <'with'> __ rest-of-line
  projection   = ident __ <'where'> __ rest-of-line
  typed-with-comment = type-ref <#'[ \\t]+--[ \\t]*'> inline-comment
  typed-field  = type-ref
  derived-value = rest-of-line

  rest-of-line = #'[^\\n}]+'

  inline-comment = #'[^\\n]+'

  (* ============ Type References ============ *)

  type-ref = union-type / single-type

  union-type = single-type (_ <'|'> _ single-type)+

  single-type = inline-obj / generic-type / optional-type / qualified-name / simple-type

  inline-obj = <'{'> _ inline-fields _ <'}'>
  inline-fields = (inline-field _ (<','> _)?)*
  inline-field = ident _ <':'> _ type-ref

  generic-type = ident <'<'> _ type-ref (_ <','> _ type-ref)* _ <'>'>

  optional-type = (inline-obj / generic-type / qualified-name / simple-type) <'?'>

  qualified-name = ident <'/'> ident

  simple-type = ident

  (* ============ Rules ============ *)

  rule-decl = <'rule'> __ ident _ description-string? _ <'{'> _ rule-body _ <'}'>

  rule-body = rule-clause+
  rule-clause = when-clause / let-clause / requires-clause / ensures-clause

  when-clause = <'when:'> _ trigger-expr _
  trigger-expr = trigger-call / trigger-binding
  trigger-call = trigger-call-name <'('> _ trigger-params? _ <')'>
  trigger-call-name = ident (<'.'> ident)*

  (* trigger-binding has two forms:
     - trigger-binding-op: var ':' source op operand?   (transitions_to, becomes, <=, etc.)
     - trigger-binding-created: var ':' source '.' 'created'  (no operand, lifecycle event) *)
  trigger-binding = trigger-binding-op / trigger-binding-created
  trigger-binding-op = ident _ <':'> _ binding-source _ trigger-operator (_ trigger-operand)?
  trigger-binding-created = ident _ <':'> _ binding-source-base <'.'> <'created'>

  binding-source = dotted-path
  binding-source-base = ident
  dotted-path = ident (<'.'> ident)*

  trigger-operator = 'transitions_to' / 'becomes' / '<=' / '>=' / '<' / '>' / '='
  trigger-operand = #'[^\\n]+'

  trigger-params = trigger-param (_ <','> _ trigger-param)*
  trigger-param = ident <'?'>? (_ <':'> _ type-ref)?

  let-clause = <'let'> __ clause-body _
  requires-clause = <'requires:'> _ clause-body _
  ensures-clause = <'ensures:'> _ (ensures-for / ensures-expr) _
  ensures-for = <'for'> __ ident __ <'in'> __ ident _ <':'> _ clause-body
  ensures-expr = clause-body

  (* ============ Clause Body Capture ============ *)
  (* Brace/paren-balanced text capture. Terminates before next clause keyword or } *)

  clause-body = balanced-chunk+
  <balanced-chunk> = brace-group / paren-group / text-chunk
  brace-group = <'{'> balanced-chunk* <'}'>
  paren-group = <'('> balanced-chunk* <')'>
  text-chunk = #'(?:(?!\\bwhen:\\b|\\blet\\b|\\brequires:\\b|\\bensures:\\b)[^{}()\\n])+' / eol
  eol = #'\\n'

  (* ============ Whitespace & Comments ============ *)

  <_> = (ws / line-comment / section-divider)*
  <__> = (ws / line-comment / section-divider)+

  <ws> = <#'[\\s]+'>
  <line-comment> = <#'(?:[ \\t]*--[^\\n]*\\n)++(?![ \\t]*@)'>
  <section-divider> = <#'-{4,}[^\\n]*\\n'>

  (* ============ Identifiers ============ *)

  ident = #'[A-Za-z_][A-Za-z0-9_]*'
  ")

;; ---------------------------------------------------------------------------
;; Parser
;; ---------------------------------------------------------------------------

(def ^:private allium-parser
  (insta/parser grammar))

;; ---------------------------------------------------------------------------
;; Transform helpers
;; ---------------------------------------------------------------------------

(defn- extract-description
  "Split a description map from the head of args.
   Returns [description rest-args] where description is a string or nil."
  [args]
  (if (and (seq args) (map? (first args)) (contains? (first args) :description))
    [(:description (first args)) (rest args)]
    [nil args]))

(defn- flatten-field-groups
  "Flatten potentially nested field group lists into a single vector."
  [groups]
  (vec (apply concat
         (map (fn [x] (if (sequential? x) x [x]))
              groups))))

(defn- field-itemise
  "If a field-item is an invariant-decl-shaped map (:type :invariant),
   convert to :field-kind shape so it merges with the field-items list
   cleanly. Top-level invariants keep :type; entity-internal ones should
   not carry :type at the field level."
  [item]
  (if (= :invariant (:type item))
    (-> item (dissoc :type) (assoc :field-kind :invariant))
    item))

(defn- ->fields
  "Build a fields vector from raw field-item groups, flattening groups
   and normalising any invariant-decl-shaped items to field-item shape."
  [field-groups]
  (mapv field-itemise (flatten-field-groups field-groups)))

(defn- text-of
  "Reconstruct text from a single balanced-chunk transform result.
   A chunk is either a string or a vector (from inv-brace-group / inv-paren-group)."
  [chunk]
  (if (sequential? chunk)
    (apply str chunk)
    (str chunk)))

(defn- text-of-chunks
  "Reconstruct text from a sequence of balanced-chunk transform results.
   Each chunk is either a string or a vector (from inv-brace-group / inv-paren-group)."
  [chunks]
  (apply str (map text-of chunks)))

;; ---------------------------------------------------------------------------
;; Transform map
;; ---------------------------------------------------------------------------

(def ^:private transforms
  {:allium-file
   (fn [header decls]
     (merge header decls))

   :header
   (fn [version]
     {:allium-version version})

   :version-number str

   :declarations
   (fn [& decls]
     {:declarations (vec (remove nil? decls))})

   :declaration identity

   ;; Use
   :use-decl
   (fn [path alias]
     {:type :use :path path :alias alias})

   :quoted-path identity
   :path-content str

   ;; Open question
   :open-question
   (fn [text] {:type :open-question :text text})

   :question-text str

   ;; Config block
   :config-block
   (fn [params]
     {:type :config, :params (vec params)})

   :config-params
   (fn [& params]
     (vec (remove nil? params)))

   :config-param
   (fn
     ([name type-ref]
      {:name name, :type-ref type-ref})
     ([name type-ref default-text]
      {:name name, :type-ref type-ref, :default (str/trim default-text)}))

   ;; Deferred
   :deferred-decl
   (fn [alias name]
     {:type :deferred :alias alias :name name})

   ;; Contract
   :contract-decl
   (fn [name & args]
     (let [[desc field-groups] (extract-description args)]
       (cond-> {:type :contract :name name
                :fields (flatten-field-groups field-groups)}
         desc (assoc :description desc))))

   ;; Default
   :default-decl
   (fn [type-name field-name body]
     {:type :default :type-name type-name :field-name field-name :body (str/trim body)})

   :default-body str

   ;; Given
   :given-block
   (fn [& bindings-or-groups]
     {:type :given
      :bindings (vec (apply concat
                       (map (fn [x] (if (sequential? x) x [x]))
                            bindings-or-groups)))})

   :given-bindings
   (fn [& bindings]
     (vec bindings))

   :given-binding
   (fn [name type-ref]
     {:name name :type-ref type-ref})

   ;; Guarantee (module-level prose promise)
   :guarantee-decl
   (fn [name]
     {:type :guarantee :name name})

   ;; Invariant
   :invariant-decl
   (fn [name & args]
     (let [[desc rest-args] (extract-description args)
           form (first rest-args)]
       (cond-> {:type :invariant, :name name, :body form}
         desc (assoc :description desc))))

   :invariant-form
   (fn [form] form)

   :invariant-for-quantification
   (fn
     ([var-name source assertion]
      {:kind :for-quantification
       :var var-name
       :source source
       :assertion (str/trim (text-of assertion))})
     ([var-name source guard assertion]
      {:kind :for-quantification
       :var var-name
       :source source
       :guard (str/trim guard)
       :assertion (str/trim (text-of assertion))}))

   :invariant-expression
   (fn [& chunks]
     {:kind :expression
      :text (str/trim (text-of-chunks chunks))})

   :invariant-assertion
   (fn [& chunks]
     (text-of-chunks chunks))

   :invariant-guard
   (fn [guard-text] (str/trim guard-text))

   :inv-brace-group
   (fn [& parts]
     (str "{" (apply str (flatten parts)) "}"))

   :inv-paren-group
   (fn [& parts]
     (str "(" (apply str (flatten parts)) ")"))

   :inv-text-chunk str

   ;; Description string — tagged to distinguish from other string args
   :description-string (fn [s] {:description s})
   :description-content str

   ;; Enum
   :enum-decl
   (fn [name & args]
     (let [[desc rest-args] (extract-description args)]
       (cond-> {:type :enum :name name :values (first rest-args)}
         desc (assoc :description desc))))

   :enum-values
   (fn [& vals]
     (vec vals))

   :enum-value
   (fn [v] v)

   :backtick-literal
   (fn [content] content)

   ;; External entity
   :external-body
   (fn [text]
     (let [trimmed (str/trim text)]
       (when-not (str/blank? trimmed) trimmed)))

   :external-entity
   (fn [name & args]
     (let [[desc rest-args] (extract-description args)
           body (first rest-args)]
       (cond-> {:type :external-entity :name name}
         desc (assoc :description desc)
         body (assoc :body body))))

   :external-value
   (fn [name & args]
     (let [[desc _] (extract-description args)]
       (cond-> {:type :external-value :name name}
         desc (assoc :description desc))))

   ;; Actor
   :actor-decl
   (fn [name actor-body]
     (merge {:type :actor, :name name} actor-body))

   :actor-body
   (fn [& clauses]
     (reduce (fn [acc clause] (merge acc clause)) {} clauses))

   :identified-by-clause
   (fn [type-ref] {:identified-by type-ref})

   :within-clause
   (fn [type-ref] {:within type-ref})

   ;; Surface
   :surface-decl
   (fn [name & args]
     (let [[desc field-groups] (extract-description args)]
       (cond-> {:type :surface :name name
                :fields (->fields field-groups)}
         desc (assoc :description desc))))

   ;; Entity / Value
   :entity-decl
   (fn [name & args]
     (let [[desc field-groups] (extract-description args)]
       (cond-> {:type :entity :name name
                :fields (->fields field-groups)}
         desc (assoc :description desc))))

   :value-decl
   (fn [name & args]
     (let [[desc field-groups] (extract-description args)]
       (cond-> {:type :value :name name
                :fields (->fields field-groups)}
         desc (assoc :description desc))))

   ;; Variant
   :variant-decl
   (fn [name & args]
     (let [[desc rest-args] (extract-description args)
           base (first rest-args)
           field-groups (rest rest-args)]
       (cond-> {:type :variant :name name :base base
                :fields (->fields field-groups)}
         desc (assoc :description desc))))

   ;; Fields
   :field-list
   (fn [& entries]
     (vec (remove nil? entries)))

   :nested-variant
   (fn [name & field-groups]
     {:field-kind :variant
      :variant-name name
      :fields (vec (apply concat
                     (map (fn [x] (if (sequential? x) x [x]))
                          field-groups)))})

   :field-entry
   (fn [name field-val]
     (assoc field-val :name name))

   ;; Provides block
   :provides-block
   (fn [entries]
     {:field-kind :provides-block :entries entries})

   :provides-entries
   (fn [& entries]
     (vec (remove nil? entries)))

   :provides-entry
   (fn [name & args]
     (let [by-tag (group-by (fn [x] (cond
                                       (and (map? x) (contains? x :provides-params)) :params
                                       (and (map? x) (contains? x :provides-return)) :return
                                       (and (map? x) (contains? x :provides-when))    :when
                                       (and (map? x) (contains? x :provides-comment)) :comment
                                       :else :unknown))
                             args)]
       (cond-> {:name name}
         (seq (:params by-tag))  (assoc :params (:provides-params (first (:params by-tag))))
         (seq (:return by-tag))  (assoc :return (:provides-return (first (:return by-tag))))
         (seq (:when by-tag))    (assoc :when (:provides-when (first (:when by-tag))))
         (seq (:comment by-tag)) (assoc :description (:provides-comment (first (:comment by-tag)))))))

   :provides-entry-name str

   :provides-params
   (fn [& param-groups]
     {:provides-params (vec (apply concat
                              (map (fn [x] (if (sequential? x) x [x]))
                                   param-groups)))})

   :provides-param-list
   (fn [& params]
     (vec params))

   :provides-param
   (fn [name & args]
     (cond-> {:name name}
       (first args) (assoc :type (first args))))

   :provides-return
   (fn [type-ref]
     {:provides-return type-ref})

   :provides-entry-comment
   (fn [text]
     {:provides-comment (str/trim text)})

   :provides-entry-when
   (fn [text]
     {:provides-when (str/trim text)})

   :provides-entry-when-text str

   ;; When guard
   :when-guard
   (fn [text]
     {:field-kind :when-guard :condition (str/trim text)})

   :when-guard-text str

   ;; Exposes block
   :exposes-block
   (fn [entries]
     {:field-kind :exposes :entries entries})

   :exposes-entries
   (fn [& entries]
     (vec entries))

   :dotted-ident str

   ;; Contracts block
   :contracts-block
   (fn [entries]
     {:field-kind :contracts :entries entries})

   :contracts-entries
   (fn [& entries]
     (vec entries))

   :contracts-entry
   (fn [verb contract-ref]
     {:verb verb :contract contract-ref})

   :contracts-verb str

   :contract-ref
   (fn [x] (if (map? x) x {:name x}))

   ;; Surface-specific fields
   :facing-field
   (fn [role type-ref]
     {:field-kind :facing, :role role, :type-ref type-ref})

   :context-field
   (fn [role type-ref]
     {:field-kind :context, :role role, :type-ref type-ref})

   :timeout-field
   (fn [rule-name]
     {:field-kind :timeout, :rule-name rule-name})

   :let-field
   (fn [name expr-text]
     {:field-kind :let, :name name, :expr (str/trim expr-text)})

   ;; Annotation (@guarantee, @guidance)
   ;; New shape: {:field-kind :annotation, :kind <kind>, :name? <name>, :body? <prose>}
   :annotation
   (fn
     ([mark] (merge {:field-kind :annotation} mark))
     ([prose mark]
      (merge {:field-kind :annotation, :body prose} mark)))

   :annotation-prose
   (fn [& lines] (clojure.string/join "\n" (map clojure.string/trim lines)))

   :prose-line
   (fn [content] content)

   :annotation-mark
   (fn
     ([kind] {:kind kind})
     ([kind name] {:kind kind, :name name}))

   ;; Related block — structured entries
   :related-block
   (fn [entries]
     {:field-kind :related, :entries entries})

   :related-entries
   (fn [& entries] (vec entries))

   :related-entry-args
   (fn [args-text] (str/trim args-text))

   :related-entry
   (fn
     ([name] {:name name})
     ([name args] {:name name, :args args}))

   ;; Transitions block — state-machine edges on an entity field
   :transitions-block
   (fn [field-name edges]
     {:field-kind :transitions
      :field field-name
      :edges edges})

   :transition-edges
   (fn [& edges] (vec edges))

   :transition-edge
   (fn [from to]
     {:from from, :to to})

   :field-value identity

   :relationship
   (fn [type-ref constraint]
     {:field-kind :relationship :type-ref type-ref :constraint constraint})

   :projection
   (fn [source predicate]
     {:field-kind :projection :source source :predicate predicate})

   ;; All declared-type field variants share :field-kind :typed; :when and
   ;; :comment are optional enrichments. Consumers iterate :typed fields and
   ;; check for the optional keys.
   :typed-with-when
   (fn [type-ref when-text]
     {:field-kind :typed, :type-ref type-ref, :when (str/trim when-text)})

   :typed-with-comment
   (fn [type-ref comment]
     {:field-kind :typed :type-ref type-ref :comment (str/trim comment)})

   :typed-field
   (fn [type-ref]
     {:field-kind :typed :type-ref type-ref})

   :derived-value
   (fn [expr]
     {:field-kind :derived :expr (str/trim expr)})

   :rest-of-line str

   :inline-comment str

   ;; Type references
   :type-ref identity

   :union-type
   (fn [& members]
     {:kind :union :members (vec members)})

   :single-type identity

   :inline-obj
   (fn [& field-groups]
     {:kind :inline-obj
      :fields (vec (apply concat
                     (map (fn [x] (if (sequential? x) x [x]))
                          field-groups)))})

   :inline-fields
   (fn [& fields]
     (vec fields))

   :inline-field
   (fn [name type-ref]
     {:name name :type-ref type-ref})

   :generic-type
   (fn [name & params]
     {:kind :generic :name name :params (vec params)})

   :optional-type
   (fn [inner]
     {:kind :optional :inner inner})

   :qualified-name
   (fn [ns-part name-part]
     {:kind :qualified :ns ns-part :name name-part})

   :simple-type
   (fn [name]
     {:kind :simple :name name})

   ;; Rules
   :rule-decl
   (fn [name & args]
     (let [[desc rest-args] (extract-description args)
           body (first rest-args)]
       (cond-> (assoc body :type :rule :name name)
         desc (assoc :description desc))))

   :rule-body
   (fn [& clauses]
     {:clauses (vec clauses)})

   :rule-clause identity

   :when-clause
   (fn [trigger]
     {:clause-type :when :trigger trigger})

   :trigger-expr identity

   :trigger-call-name
   (fn [& idents] (clojure.string/join "." idents))

   :trigger-call
   (fn [name & param-groups]
     (let [params (vec (apply concat
                         (map (fn [x] (if (sequential? x) x [x]))
                              param-groups)))]
       {:kind :call :name name :params params}))

   :trigger-binding identity

   :trigger-binding-op
   (fn
     ([var-name source operator]
      {:kind :binding, :var var-name, :source source, :operator operator})
     ([var-name source operator operand]
      {:kind :binding, :var var-name, :source source
       :operator operator, :operand operand}))

   :trigger-binding-created
   (fn [var-name source-base]
     {:kind :binding, :var var-name, :source source-base, :operator "created"})

   :binding-source
   (fn [dotted-path-result]
     ;; dotted-path-result may be a vec (from :dotted-path transform) or a
     ;; raw value depending on how instaparse hands it back. Coerce to string.
     (if (vector? dotted-path-result)
       (clojure.string/join "." dotted-path-result)
       (str dotted-path-result)))

   :binding-source-base
   (fn [ident] ident)

   :dotted-path
   (fn [& idents] (vec idents))

   :trigger-operator
   (fn [op] op)

   :trigger-operand
   (fn [s] (str/trim s))

   :trigger-params
   (fn [& params]
     (vec params))

   :trigger-param
   (fn [name & args]
     (cond-> {:name name}
       (first args) (assoc :type-ref (first args))))

   :let-clause
   (fn [& body-parts]
     {:clause-type :let :body (str/trim (apply str (flatten body-parts)))})

   :requires-clause
   (fn [& body-parts]
     {:clause-type :requires :body (str/trim (apply str (flatten body-parts)))})

   :ensures-clause
   (fn [body-or-for]
     ;; body-or-for is either an ensures-for result (map) or an ensures-expr result (string)
     (if (map? body-or-for)
       (merge {:clause-type :ensures} body-or-for)
       {:clause-type :ensures, :body (str/trim body-or-for)}))

   :ensures-for
   (fn [var-name collection body]
     {:kind :for-iteration
      :var var-name
      :collection collection
      :body (str/trim body)})

   :ensures-expr
   (fn [body] (str/trim body))

   ;; Clause body
   :clause-body
   (fn [& parts]
     (apply str (flatten parts)))

   :brace-group
   (fn [& parts]
     (str "{" (apply str (flatten parts)) "}"))

   :paren-group
   (fn [& parts]
     (str "(" (apply str (flatten parts)) ")"))

   :text-chunk str
   :eol (constantly "\n")

   ;; Identifiers
   :ident str})

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn parse-allium
  "Parse an Allium specification string into an AST map.
   Returns a map with :allium-version and :declarations on success,
   or an instaparse failure object on parse error."
  {:malli/schema [:=> [:cat :string] :map]}
  [text]
  (let [tree (allium-parser text)]
    (if (insta/failure? tree)
      tree
      (insta/transform transforms tree))))

(defn parse-file
  "Parse an Allium specification file into an AST map.
   Reads the file at the given path and parses its contents."
  {:malli/schema [:=> [:cat :FilePath] :map]}
  [path]
  (parse-allium (slurp path)))
