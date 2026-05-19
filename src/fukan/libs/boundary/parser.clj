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
  (:require [instaparse.core :as insta]))

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
  declaration = use-decl   (* tasks 1+ add fn-decl, exports-decl, subsystem-decl *)

  (* ============ Use (placeholder — Task 1 implements) ============ *)

  use-decl = <'use'> __ <'\"'> #'[^\"]*' <'\"'> __ <'as'> __ #'[a-zA-Z_][a-zA-Z0-9_]*'

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
   :use-decl         (fn [path alias]
                       {:type :use :path path :alias alias})
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
