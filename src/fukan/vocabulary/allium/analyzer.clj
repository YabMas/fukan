(ns fukan.vocabulary.allium.analyzer
  "Per-file Allium AST → kernel content (with Allium::* tag applications).
   The entry point `analyze-file` takes a Model, an AST, and a coordinate;
   returns the Model extended with this file's content.")
