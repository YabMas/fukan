(ns canvas.libs.allium.parser
  "Canvas port of libs/allium/parser.allium + parser.boundary.

   Coverage:
     - record ParsedAllium          → construction/record (2 fields; List<Any>)
     - record ParseFailure          → construction/record (1 field: Any)
     - invariant ParserPure         → vocab.behavioral/invariant
     - invariant ParseFileDelegates → vocab.behavioral/invariant
     - invariant DeclarationOrderPreserved → vocab.behavioral/invariant
     - fn parse_allium              → construction/function
     - fn parse_file                → construction/function

   Shape grammar:
     - ParsedAllium.declarations is List<Any> → (list-of :Any)

   Note: parse_allium return type is ParsedAllium | ParseFailure (sum type).
         Represented as (sum-of :ParsedAllium :ParseFailure)."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function record]]
            [fukan.canvas.vocab.behavioral :refer [invariant]]))

(defn ^:export build-canvas []
  (h/with-canvas
    (h/within-module "libs.allium.parser"

      ;; Value types from parser.allium

      (record "ParsedAllium"
        "Successful parse output. allium_version is the parsed header version
         number; declarations is an ordered list of declaration nodes, each a
         map shaped by its grammar production (use, entity, value, variant,
         rule, surface, invariant, etc.)."
        (field allium_version :String)
        (field declarations   (list-of :Any)))

      (record "ParseFailure"
        "Instaparse failure object returned verbatim when the input does not
         parse. Opaque from the spec's perspective: the consumer inspects it
         for diagnostics rather than driving behaviour from its shape."
        (field reason :Any))

      ;; Invariants from parser.allium

      (invariant "ParserPure"
        "parse_allium is a pure function of its String input. Same text in,
         same AST out — no global state, no caches, no side effects."
        (holds-that "parser-pure"))

      (invariant "ParseFileDelegates"
        "parse_file performs exactly one act of I/O — reading the file at the
         given path — and delegates all parsing work to parse_allium on the
         file's contents."
        (holds-that "parse-file-delegates"))

      (invariant "DeclarationOrderPreserved"
        "The order of declarations in the source text is preserved in the
         declarations list of the resulting ParsedAllium. Downstream consumers
         may rely on source order for diagnostics and for resolving forward
         references."
        (holds-that "declaration-order-preserved"))

      ;; Public functions from parser.boundary
      ;; Return type is ParsedAllium | ParseFailure on grammar error.

      (function "parse_allium"
        "Parse an Allium specification string into an AST map. Returns a
         ParsedAllium on success or a ParseFailure on grammar error."
        (takes [text :String])
        (gives (sum-of :ParsedAllium :ParseFailure)))

      (function "parse_file"
        "Read the file at path and parse its contents as Allium. Thin wrapper
         over parse_allium that handles file I/O."
        (takes [path :String])
        (gives (sum-of :ParsedAllium :ParseFailure))))))
