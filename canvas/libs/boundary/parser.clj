(ns canvas.libs.boundary.parser
  "Canvas port of libs/boundary/parser.allium + parser.boundary.

   Structurally parallel to canvas.libs.allium.parser: both parsers share
   the same lifecycle (text → AST or failure) and the same purity guarantees,
   but operate on distinct grammars and emit distinct AST shapes.

   Coverage:
     - record ParsedBoundary        → construction/record (2 fields; List<Any>)
     - record ParseFailure          → construction/record (1 field: Any)
     - invariant ParserPure         → vocab.behavioral/invariant
     - invariant ParseFileDelegates → vocab.behavioral/invariant
     - invariant DeclarationOrderPreserved → vocab.behavioral/invariant
     - fn parse_boundary            → construction/function
     - fn parse_file                → construction/function

   Shape grammar:
     - ParsedBoundary.declarations is List<Any> → (list-of :Any)
     - boundary_version: Integer (not String — boundary grammar uses integer)"
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function record]]
            [fukan.canvas.vocab.behavioral :refer [invariant]]))

(defn ^:export build-canvas []
  (h/with-canvas
    (h/within-module "libs.boundary.parser"

      ;; Value types from parser.allium

      (record "ParsedBoundary"
        "Successful parse output. boundary_version is the parsed header version
         number (integer-typed in the boundary grammar); declarations is an
         ordered list of declaration nodes — use, fn (in declare-new /
         local-attach / foreign-attach forms), exports, or subsystem."
        (field boundary_version :Integer)
        (field declarations     (list-of :Any)))

      (record "ParseFailure"
        "Instaparse failure object returned verbatim when the input does not
         parse. Opaque from the spec's perspective."
        (field reason :Any))

      ;; Invariants from parser.allium

      (invariant "ParserPure"
        "parse_boundary is a pure function of its String input. Same text in,
         same AST out — no global state, no side effects."
        (holds-that "parser-pure"))

      (invariant "ParseFileDelegates"
        "parse_file performs exactly one act of I/O — reading the file at the
         given path — and delegates all parsing work to parse_boundary on the
         file's contents."
        (holds-that "parse-file-delegates"))

      (invariant "DeclarationOrderPreserved"
        "The order of declarations in the source text is preserved in the
         declarations list of the resulting ParsedBoundary."
        (holds-that "declaration-order-preserved"))

      ;; Public functions from parser.boundary

      (function "parse_boundary"
        "Parse a .boundary specification string into an AST map. Returns a
         ParsedBoundary on success or a ParseFailure on grammar error."
        (takes [text :String])
        (gives (sum-of :ParsedBoundary :ParseFailure)))

      (function "parse_file"
        "Read the file at path and parse its contents as Boundary. Thin wrapper
         over parse_boundary that handles file I/O."
        (takes [path :String])
        (gives (sum-of :ParsedBoundary :ParseFailure))))))
