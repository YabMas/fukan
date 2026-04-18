(ns fukan.libs.boundary.parser
  "Instaparse-based parser for .boundary module boundary files.
   Converts boundary definitions into a Clojure AST suitable for
   model building. The .boundary language declares module public APIs:
   functions, exposed types (by reference to allium), and guarantees."
  (:require [instaparse.core :as insta]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Grammar
;; ---------------------------------------------------------------------------

(def grammar
  "PEG grammar for the .boundary module boundary language.

   Three constructs:
   - use: import type aliases from other modules
   - fn: public function with typed signature and optional doc
   - exposes: reference to an allium-defined type, optionally narrowed

   Module description is captured from the leading comment block.
   Prose promises about the module live in its .allium sibling as
   module-level `guarantee` declarations.
   Type expression grammar (for fn params/returns) matches Allium."

  "
  (* ============ Top-level ============ *)

  boundary-file = _ declarations _

  declarations = (declaration _)*
  declaration = use-decl / fn-decl / exposes-decl

  (* ============ Use ============ *)

  use-decl = <'use'> __ quoted-path __ <'as'> __ ident

  quoted-path = <'\"'> path-content <'\"'>
  path-content = #'[^\"]*'

  (* ============ Function ============ *)

  fn-decl = <'fn'> __ fn-name fn-params? fn-return? fn-doc?
  fn-name = #'[a-z_][a-zA-Z0-9_]*'
  fn-params = <'('> _ fn-param-list? _ <')'>
  fn-param-list = fn-param (_ <','> _ fn-param)*
  fn-param = ident _ <':'> _ type-ref
  fn-return = _ <'->'> _ type-ref
  fn-doc = _ doc-comment

  (* ============ Exposes ============ *)

  exposes-decl = <'exposes'> __ ident exposes-fields?
  exposes-fields = _ <'{'> _ exposes-field-list _ <'}'>
  exposes-field-list = ident (_ <','> _ ident)*

  (* ============ Doc Comments ============ *)

  doc-comment = doc-line+
  <doc-line> = <#'[ \\t]*--[ \\t]?'> doc-text <#'\\n'>
  doc-text = #'[^\\n]*'

  (* ============ Type References ============ *)
  (* Used in fn params and return types. Matches Allium type grammar. *)

  type-ref = union-type / single-type

  union-type = single-type (_ <'|'> _ single-type)+

  single-type = generic-type / optional-type / qualified-name / simple-type

  generic-type = ident <'<'> _ type-ref (_ <','> _ type-ref)* _ <'>'>

  optional-type = (generic-type / qualified-name / simple-type) <'?'>

  qualified-name = ident <'/'> ident

  simple-type = ident

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

(def ^:private boundary-parser
  (insta/parser grammar))

;; ---------------------------------------------------------------------------
;; Transform map
;; ---------------------------------------------------------------------------

(def ^:private transforms
  {:boundary-file
   (fn [decls]
     decls)

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

   ;; Function
   :fn-decl
   (fn [name & args]
     (let [by-tag (group-by (fn [x]
                              (cond
                                (and (map? x) (contains? x :fn-params)) :params
                                (and (map? x) (contains? x :fn-return)) :return
                                (and (map? x) (contains? x :fn-doc)) :doc
                                :else :unknown))
                            args)]
       (cond-> {:type :fn :name name}
         (seq (:params by-tag)) (assoc :params (:fn-params (first (:params by-tag))))
         (seq (:return by-tag)) (assoc :return (:fn-return (first (:return by-tag))))
         (seq (:doc by-tag)) (assoc :description (:fn-doc (first (:doc by-tag)))))))

   :fn-name str

   :fn-params
   (fn [& param-groups]
     {:fn-params (vec (apply concat
                        (map (fn [x] (if (sequential? x) x [x]))
                             param-groups)))})

   :fn-param-list
   (fn [& params]
     (vec params))

   :fn-param
   (fn [name type-ref]
     {:name name :type type-ref})

   :fn-return
   (fn [type-ref]
     {:fn-return type-ref})

   :fn-doc
   (fn [text]
     {:fn-doc text})

   ;; Exposes
   :exposes-decl
   (fn [name & args]
     (let [fields (first args)]
       (cond-> {:type :exposes :name name}
         fields (assoc :fields fields))))

   :exposes-fields
   (fn [& field-groups]
     (vec (apply concat
            (map (fn [x] (if (sequential? x) x [x]))
                 field-groups))))

   :exposes-field-list
   (fn [& fields]
     (vec fields))

   ;; Doc comments
   :doc-comment
   (fn [& lines]
     (str/join "\n" (map str/trim lines)))

   :doc-text str

   ;; Type references
   :type-ref identity

   :union-type
   (fn [& members]
     {:kind :union :members (vec members)})

   :single-type identity

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

   ;; Identifiers
   :ident str})

;; ---------------------------------------------------------------------------
;; Description extraction from raw text
;; ---------------------------------------------------------------------------

(defn- strip-comment-prefix
  "Strip leading '-- ' or '--' from a comment line."
  [line]
  (let [trimmed (str/trim line)]
    (cond
      (str/starts-with? trimmed "-- ") (subs trimmed 3)
      (str/starts-with? trimmed "--") (subs trimmed 2)
      :else trimmed)))

(def ^:private section-divider-re #"^(?:--\s*)?[-=]{4,}")

(defn extract-module-description
  "Extract the module description from the leading comment block.
   Returns the text of all comment lines before the first declaration
   or section divider."
  [text]
  (let [lines (str/split-lines text)]
    (->> lines
         (take-while (fn [line]
                       (let [trimmed (str/trim line)]
                         (and (or (str/blank? trimmed)
                                  (str/starts-with? trimmed "--"))
                              (not (re-find section-divider-re trimmed))))))
         (map strip-comment-prefix)
         (remove str/blank?)
         (str/join "\n")
         not-empty)))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn parse-boundary
  "Parse a .boundary specification string into an AST map.
   Returns a map with :declarations on success,
   or an instaparse failure object on parse error."
  {:malli/schema [:=> [:cat :string] :map]}
  [text]
  (let [tree (boundary-parser text)]
    (if (insta/failure? tree)
      tree
      (insta/transform transforms tree))))

(defn parse-file
  "Parse a .boundary specification file into an AST map.
   Reads the file at the given path and parses its contents."
  {:malli/schema [:=> [:cat :FilePath] :map]}
  [path]
  (parse-boundary (slurp path)))
