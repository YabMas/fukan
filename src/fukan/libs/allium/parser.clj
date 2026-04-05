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
                external-entity / external-value / surface-decl / variant-decl /
                entity-decl / value-decl / rule-decl / invariant-decl

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

  config-block = <'config'> _ <'{'> config-body <'}'>
  config-body = #'[^}]*'

  (* ============ Enum ============ *)

  enum-decl = <'enum'> __ ident _ description-string? _ <'{'> _ enum-values _ <'}'>
  enum-values = ident (_ <'|'>? _ ident)*

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

  (* ============ Invariant ============ *)

  invariant-decl = <'invariant'> __ ident _ description-string? _ <'{'> _ invariant-body _ <'}'>
  invariant-body = invariant-chunk+
  <invariant-chunk> = inv-brace-group / inv-paren-group / inv-text-chunk
  inv-brace-group = '{' invariant-chunk* '}'
  inv-paren-group = '(' invariant-chunk* ')'
  inv-text-chunk = #'[^{}()\\n]+' / eol

  (* ============ Description String ============ *)

  description-string = <'\"'> description-content <'\"'>
  description-content = #'[^\"]*'

  (* ============ Fields ============ *)

  field-list = (field-item _ (<','> _)?)*
  <field-item> = provides-block / related-block / exposes-block / nested-variant / invariant-decl / annotation / when-guard / facing-field / context-field / field-entry

  (* Exposes block: exposes: followed by dotted identifiers *)
  exposes-block = <'exposes'> _ <':'> _ exposes-entries
  exposes-entries = (dotted-ident _)+
  dotted-ident = #'[A-Za-z_][A-Za-z0-9_.]*'

  (* Surface-specific: facing role: Type, context role: Type *)
  facing-field = <'facing'> __ ident _ <':'> _ type-ref
  context-field = <'context'> __ ident _ <':'> _ type-ref

  (* Annotation: @guarantee Name or @guidance — absorbed with trailing comments *)
  annotation = <'@'> ident (__ ident)?

  (* When guard: when condition — appears after provides entries *)
  when-guard = <'when'> __ when-guard-text
  when-guard-text = #'[^\\n}]+'

  (* Related block: related: followed by indented entries, captured as text *)
  related-block = <'related'> _ <':'> _ related-body
  related-body = related-line+
  <related-line> = #'[^\n}@]+' / eol
  field-entry = ident _ <':'> _ field-value

  (* Provides block: single 'provides:' with typed operation entries.
     Uses newline-based separation between entries so that inline
     comments are captured by provides-entry-comment, not swallowed
     by the line-comment rule in _ *)
  provides-block = <'provides'> _ <':'> _ provides-entries
  provides-entries = (provides-entry provides-entry-sep)*
  <provides-entry-sep> = <#'[ \\t]*\\n'> _
  provides-entry = provides-entry-name provides-params? provides-return? provides-entry-comment?
  provides-entry-name = #'[A-Za-z_][A-Za-z0-9_]*+(?![ \\t]*:)'
  provides-params = <'('> _ provides-param-list? _ <')'>
  provides-param-list = provides-param (_ <','> _ provides-param)*
  provides-param = ident (_ <':'> _ type-ref)?
  provides-return = <#'[ \\t]+'> <'->'> <#'[ \\t]+'> type-ref
  provides-entry-comment = <#'[ \\t]+--[ \\t]*'> inline-comment

  nested-variant = <'variant'> __ ident _ <'{'> _ field-list _ <'}'>

  (* Ordered alternation: try relationship, projection, typed-with-comment, typed-field, then derived *)
  field-value = relationship / projection / typed-with-comment / typed-field / derived-value

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
  trigger-call = ident <'('> _ trigger-params? _ <')'>
  trigger-binding = trigger-binding-text
  trigger-binding-text = #'[^\\n{}]+'
  trigger-params = trigger-param (_ <','> _ trigger-param)*
  trigger-param = ident (_ <':'> _ type-ref)?

  let-clause = <'let'> __ clause-body _
  requires-clause = <'requires:'> _ clause-body _
  ensures-clause = <'ensures:'> _ clause-body _

  (* ============ Clause Body Capture ============ *)
  (* Brace/paren-balanced text capture. Terminates before next clause keyword or } *)

  clause-body = balanced-chunk+
  <balanced-chunk> = brace-group / paren-group / text-chunk
  brace-group = '{' balanced-chunk* '}'
  paren-group = '(' balanced-chunk* ')'
  text-chunk = #'(?:(?!\\bwhen:\\b|\\blet\\b|\\brequires:\\b|\\bensures:\\b)[^{}()\\n])+' / eol
  eol = #'\\n'

  (* ============ Whitespace & Comments ============ *)

  <_> = (ws / line-comment / section-divider)*
  <__> = (ws / line-comment / section-divider)+

  <ws> = <#'[\\s]+'>
  <line-comment> = <#'--[^\\n]*\\n'>
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
   (fn [body] {:type :config :body (str/trim body)})

   :config-body str

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

   ;; Invariant
   :invariant-decl
   (fn [name & args]
     (let [[desc rest-args] (extract-description args)
           body (first rest-args)]
       (cond-> {:type :invariant :field-kind :invariant :name name :body body}
         desc (assoc :description desc))))

   :invariant-body
   (fn [& parts]
     (str/trim (apply str (flatten parts))))

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

   ;; Surface
   :surface-decl
   (fn [name & args]
     (let [[desc field-groups] (extract-description args)]
       (cond-> {:type :surface :name name
                :fields (flatten-field-groups field-groups)}
         desc (assoc :description desc))))

   ;; Entity / Value
   :entity-decl
   (fn [name & args]
     (let [[desc field-groups] (extract-description args)]
       (cond-> {:type :entity :name name
                :fields (flatten-field-groups field-groups)}
         desc (assoc :description desc))))

   :value-decl
   (fn [name & args]
     (let [[desc field-groups] (extract-description args)]
       (cond-> {:type :value :name name
                :fields (flatten-field-groups field-groups)}
         desc (assoc :description desc))))

   ;; Variant
   :variant-decl
   (fn [name & args]
     (let [[desc rest-args] (extract-description args)
           base (first rest-args)
           field-groups (rest rest-args)]
       (cond-> {:type :variant :name name :base base
                :fields (flatten-field-groups field-groups)}
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
                                       (and (map? x) (contains? x :provides-comment)) :comment
                                       :else :unknown))
                             args)]
       (cond-> {:name name}
         (seq (:params by-tag)) (assoc :params (:provides-params (first (:params by-tag))))
         (seq (:return by-tag)) (assoc :return (:provides-return (first (:return by-tag))))
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

   ;; Surface-specific fields
   :facing-field
   (fn [name type-ref]
     {:field-kind :facing :name name :type-ref type-ref})

   :context-field
   (fn [name type-ref]
     {:field-kind :context :name name :type-ref type-ref})

   ;; Annotation (@guarantee, @guidance)
   :annotation
   (fn [kind & args]
     {:field-kind :annotation :kind kind :name (first args)})

   ;; Related block
   :related-block
   (fn [& body-parts]
     {:field-kind :related :body (str/trim (apply str (flatten body-parts)))})

   :related-body
   (fn [& parts]
     (apply str (flatten parts)))

   :field-value identity

   :relationship
   (fn [type-ref constraint]
     {:field-kind :relationship :type-ref type-ref :constraint constraint})

   :projection
   (fn [source predicate]
     {:field-kind :projection :source source :predicate predicate})

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

   :trigger-call
   (fn [name & param-groups]
     (let [params (vec (apply concat
                         (map (fn [x] (if (sequential? x) x [x]))
                              param-groups)))]
       {:kind :call :name name :params params}))

   :trigger-binding
   (fn [text]
     {:kind :binding :text (str/trim text)})

   :trigger-binding-text str

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
   (fn [& body-parts]
     {:clause-type :ensures :body (str/trim (apply str (flatten body-parts)))})

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
